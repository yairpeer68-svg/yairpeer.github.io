import asyncio
import os
import secrets
import tempfile
import uuid
import zipfile
from datetime import UTC, datetime
from fastapi import APIRouter, Depends, File, Query, Request, UploadFile
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession
from fastapi.responses import StreamingResponse
from app.api.dependencies.auth import current_user
from app.core.config import Settings, get_settings
from app.core.errors import AppError
from app.db.session import get_session
from app.engineering.code_index import CodeIndex
from app.engineering.memory import search_memory
from app.engineering.git_service import GitService
from app.engineering.workspace import Workspace
from app.models.entities import EngineeringApproval, EngineeringCheckpoint, EngineeringEvent, EngineeringProject, EngineeringRun, EngineeringTask, User
from app.repositories.audit import write_audit
from app.schemas.common import MessageResponse
from app.schemas.engineering import ApprovalDecision, ApprovalOut, EventOut, ProjectCreate, ProjectOut, RunCreate, RunOut, TaskOut

router=APIRouter()

def workspace(project,settings): return Workspace(settings,str(project.user_id),project.workspace_key)
async def owned_project(db,pid,user):
    p=await db.get(EngineeringProject,pid)
    if not p or p.user_id!=user.id: raise AppError('PROJECT_NOT_FOUND','Project not found',404)
    return p
async def owned_run(db,rid,user):
    r=await db.get(EngineeringRun,rid)
    if not r or r.user_id!=user.id: raise AppError('RUN_NOT_FOUND','Run not found',404)
    return r

async def _count(db,stmt)->int:
    """Run a COUNT(*) select and normalise the result to an int."""
    return int(await db.scalar(stmt) or 0)

async def project_active_runs(db,project_id):
    return await _count(db,select(func.count(EngineeringRun.id)).where(EngineeringRun.project_id==project_id,EngineeringRun.status.in_(['queued','running','waiting_approval'])))

async def ensure_project_idle(db,project_id):
    if await project_active_runs(db,project_id): raise AppError('PROJECT_BUSY','Project already has an active engineering run',409)

async def ensure_user_run_capacity(db,user,settings):
    limit=settings.ENGINEERING_MAX_ACTIVE_RUNS_PER_USER
    if limit<=0: return
    active=await _count(db,select(func.count(EngineeringRun.id)).where(EngineeringRun.user_id==user.id,EngineeringRun.status.in_(['queued','running','waiting_approval'])))
    if active>=limit: raise AppError('RUN_LIMIT_REACHED',f'You already have {active} active engineering runs',429)

async def ensure_user_project_capacity(db,user,settings):
    limit=settings.ENGINEERING_MAX_PROJECTS_PER_USER
    if limit<=0: return
    count=await _count(db,select(func.count(EngineeringProject.id)).where(EngineeringProject.user_id==user.id,EngineeringProject.status!='deleted'))
    if count>=limit: raise AppError('PROJECT_LIMIT_REACHED',f'Project limit of {limit} reached for this account',429)

async def lock_project(db,project_id):
    await db.execute(select(EngineeringProject.id).where(EngineeringProject.id==project_id).with_for_update())

@router.post('/projects',response_model=ProjectOut,status_code=201)
async def create_project(payload:ProjectCreate,request:Request,user:User=Depends(current_user),db:AsyncSession=Depends(get_session),settings:Settings=Depends(get_settings)):
    if not settings.ENGINEERING_ENABLED: raise AppError('ENGINEERING_DISABLED','Engineering runtime is disabled',503)
    await ensure_user_project_capacity(db,user,settings)
    key=secrets.token_urlsafe(18).replace('-','').replace('_','')[:24]
    item=EngineeringProject(user_id=user.id,name=payload.name,description=payload.description,project_type=payload.project_type,workspace_key=key,settings_json=payload.settings)
    db.add(item); await db.flush(); workspace(item,settings); await write_audit(db,request,'engineering_project_create',user.id,'engineering_project',str(item.id)); await db.commit(); await db.refresh(item); return item

@router.get('/projects',response_model=list[ProjectOut])
async def projects(user:User=Depends(current_user),db:AsyncSession=Depends(get_session)):
    return list((await db.scalars(select(EngineeringProject).where(EngineeringProject.user_id==user.id,EngineeringProject.status!='deleted').order_by(EngineeringProject.updated_at.desc()).limit(200))).all())

@router.get('/projects/{project_id}',response_model=ProjectOut)
async def project(project_id:uuid.UUID,user:User=Depends(current_user),db:AsyncSession=Depends(get_session)): return await owned_project(db,project_id,user)

@router.post('/projects/{project_id}/archive')
async def upload_archive(project_id:uuid.UUID,request:Request,file:UploadFile=File(...),user:User=Depends(current_user),db:AsyncSession=Depends(get_session),settings:Settings=Depends(get_settings)):
    p=await owned_project(db,project_id,user); await lock_project(db,p.id); await ensure_project_idle(db,p.id)
    if not (file.filename or '').lower().endswith('.zip'): raise AppError('ZIP_REQUIRED','Upload a ZIP archive',422)
    limit=settings.ENGINEERING_MAX_ARCHIVE_BYTES
    chunks=[]; total=0
    while True:
        chunk=await file.read(1024*1024)
        if not chunk: break
        total+=len(chunk)
        if total>limit: raise AppError('ARCHIVE_TOO_LARGE','Archive exceeds upload limit',413)
        chunks.append(chunk)
    data=b''.join(chunks); chunks.clear()
    result=await asyncio.to_thread(workspace(p,settings).import_zip,data); result['code_index']='stale'; p.updated_at=datetime.now(UTC)
    await write_audit(db,request,'engineering_archive_import',user.id,'engineering_project',str(p.id),{'filename':file.filename,**result}); await db.commit(); return result

@router.get('/projects/{project_id}/tree')
async def tree(project_id:uuid.UUID,path:str=Query('',max_length=1000),user:User=Depends(current_user),db:AsyncSession=Depends(get_session),settings:Settings=Depends(get_settings)):
    p=await owned_project(db,project_id,user); return workspace(p,settings).tree(path)

@router.get('/projects/{project_id}/file')
async def read_file(project_id:uuid.UUID,path:str=Query(...,min_length=1,max_length=1000),user:User=Depends(current_user),db:AsyncSession=Depends(get_session),settings:Settings=Depends(get_settings)):
    p=await owned_project(db,project_id,user); return {'path':path,'content':workspace(p,settings).read_text(path)}

@router.post('/projects/{project_id}/runs',response_model=RunOut,status_code=201)
async def create_run(project_id:uuid.UUID,payload:RunCreate,request:Request,user:User=Depends(current_user),db:AsyncSession=Depends(get_session),settings:Settings=Depends(get_settings)):
    p=await owned_project(db,project_id,user); await lock_project(db,p.id); await ensure_project_idle(db,p.id); await ensure_user_run_capacity(db,user,settings); item=EngineeringRun(project_id=p.id,user_id=user.id,goal=payload.goal); db.add(item); await db.flush(); await write_audit(db,request,'engineering_run_create',user.id,'engineering_run',str(item.id)); await db.commit(); await db.refresh(item); return item

@router.get('/projects/{project_id}/runs',response_model=list[RunOut])
async def runs(project_id:uuid.UUID,user:User=Depends(current_user),db:AsyncSession=Depends(get_session)):
    p=await owned_project(db,project_id,user); return list((await db.scalars(select(EngineeringRun).where(EngineeringRun.project_id==p.id).order_by(EngineeringRun.created_at.desc()).limit(100))).all())

@router.post('/runs/{run_id}/start',response_model=MessageResponse)
async def start_run(run_id:uuid.UUID,user:User=Depends(current_user),db:AsyncSession=Depends(get_session)):
    owned=await owned_run(db,run_id,user)
    locked=await db.scalar(select(EngineeringRun).where(EngineeringRun.id==owned.id).with_for_update())
    # The row can disappear between the ownership read and the locking read (cascade from a
    # concurrent project delete); dereferencing it unguarded produced a 500.
    if locked is None: raise AppError('RUN_NOT_FOUND','Run not found',404)
    r: EngineeringRun = locked
    if r.status not in {'queued','waiting_approval'}: raise AppError('RUN_NOT_STARTABLE','Run is already active or finished',409)
    if r.stage in {'queued_worker','resume_queued'}: raise AppError('RUN_ALREADY_QUEUED','Run is already queued for a worker',409)
    if r.status=='waiting_approval':
        pending=await _count(db,select(func.count(EngineeringApproval.id)).where(EngineeringApproval.run_id==r.id,EngineeringApproval.status=='pending'))
        if pending: raise AppError('APPROVAL_REQUIRED','Decide all pending approvals before resuming the run',409)
        r.stage='resume_queued'
    else:
        r.stage='queued_worker'
    await db.commit()
    from app.workers.worker import engineering_run_job
    try: engineering_run_job.send(str(r.id))
    except Exception:
        r.stage='approval' if r.status=='waiting_approval' else 'planning'; await db.commit(); raise
    return MessageResponse(message='Run queued')

@router.get('/runs/{run_id}',response_model=RunOut)
async def run_detail(run_id:uuid.UUID,user:User=Depends(current_user),db:AsyncSession=Depends(get_session)): return await owned_run(db,run_id,user)

@router.get('/runs/{run_id}/tasks',response_model=list[TaskOut])
async def tasks(run_id:uuid.UUID,user:User=Depends(current_user),db:AsyncSession=Depends(get_session)):
    r=await owned_run(db,run_id,user); return list((await db.scalars(select(EngineeringTask).where(EngineeringTask.run_id==r.id).order_by(EngineeringTask.sequence))).all())

@router.get('/runs/{run_id}/events',response_model=list[EventOut])
async def events(run_id:uuid.UUID,limit:int=Query(100,ge=1,le=500),user:User=Depends(current_user),db:AsyncSession=Depends(get_session)):
    r=await owned_run(db,run_id,user); rows=list((await db.scalars(select(EngineeringEvent).where(EngineeringEvent.run_id==r.id).order_by(EngineeringEvent.created_at.desc()).limit(limit))).all()); return list(reversed(rows))

@router.post('/runs/{run_id}/cancel',response_model=MessageResponse)
async def cancel(run_id:uuid.UUID,request:Request,user:User=Depends(current_user),db:AsyncSession=Depends(get_session)):
    r=await owned_run(db,run_id,user)
    if r.status in {'completed','failed','cancelled'}: return MessageResponse(message='Run already finished')
    r.cancel_requested=True
    if r.status in {'queued','waiting_approval'}:
        r.status='cancelled'; r.stage='cancelled'; r.finished_at=datetime.now(UTC)
        pending=list((await db.scalars(select(EngineeringApproval).where(EngineeringApproval.run_id==r.id,EngineeringApproval.status=='pending'))).all())
        for approval in pending:
            approval.status='rejected'; approval.decision_note='Run cancelled before decision'; approval.decided_at=datetime.now(UTC)
    await write_audit(db,request,'engineering_run_cancel',user.id,'engineering_run',str(r.id)); await db.commit()
    return MessageResponse(message='Run cancelled' if r.status=='cancelled' else 'Cancellation requested')

@router.get('/runs/{run_id}/approvals',response_model=list[ApprovalOut])
async def approvals(run_id:uuid.UUID,user:User=Depends(current_user),db:AsyncSession=Depends(get_session)):
    r=await owned_run(db,run_id,user); return list((await db.scalars(select(EngineeringApproval).where(EngineeringApproval.run_id==r.id).order_by(EngineeringApproval.created_at.desc()))).all())

@router.post('/approvals/{approval_id}/decision',response_model=ApprovalOut)
async def decide(approval_id:uuid.UUID,payload:ApprovalDecision,request:Request,user:User=Depends(current_user),db:AsyncSession=Depends(get_session)):
    a=await db.scalar(select(EngineeringApproval).where(EngineeringApproval.id==approval_id).with_for_update())
    if not a: raise AppError('APPROVAL_NOT_FOUND','Approval not found',404)
    r=await owned_run(db,a.run_id,user)
    if a.status!='pending': raise AppError('APPROVAL_DECIDED','Approval was already decided',409)
    a.status=payload.decision; a.decision_by_user_id=user.id; a.decision_note=payload.note; a.decided_at=datetime.now(UTC)
    await write_audit(db,request,'engineering_approval_decision',user.id,'engineering_approval',str(a.id),{'decision':payload.decision}); await db.commit(); await db.refresh(a)
    pending=await _count(db,select(func.count(EngineeringApproval.id)).where(EngineeringApproval.run_id==r.id,EngineeringApproval.status=='pending'))
    if pending==0 and r.status=='waiting_approval':
        r.stage='resume_queued'; await db.commit()
        from app.workers.worker import engineering_run_job
        try: engineering_run_job.send(str(r.id))
        except Exception:
            r.stage='approval'; await db.commit(); raise
    return a

@router.get('/projects/{project_id}/memory')
async def memory(project_id:uuid.UUID,q:str=Query(...,min_length=2,max_length=1000),user:User=Depends(current_user),db:AsyncSession=Depends(get_session)):
    p=await owned_project(db,project_id,user); return await search_memory(db,p.id,q,20)


@router.get('/projects/{project_id}/checkpoints')
async def checkpoints(project_id:uuid.UUID,user:User=Depends(current_user),db:AsyncSession=Depends(get_session)):
    p=await owned_project(db,project_id,user)
    rows=list((await db.scalars(select(EngineeringCheckpoint).where(EngineeringCheckpoint.project_id==p.id).order_by(EngineeringCheckpoint.created_at.desc()).limit(100))).all())
    return [{'id':str(x.id),'run_id':str(x.run_id) if x.run_id else None,'label':x.label,'git_commit':x.git_commit,'manifest_hash':x.manifest_hash,'created_at':x.created_at} for x in rows]

@router.post('/projects/{project_id}/checkpoints/{checkpoint_id}/rollback',response_model=MessageResponse)
async def rollback_checkpoint(project_id:uuid.UUID,checkpoint_id:uuid.UUID,request:Request,user:User=Depends(current_user),db:AsyncSession=Depends(get_session),settings:Settings=Depends(get_settings)):
    p=await owned_project(db,project_id,user)
    await lock_project(db,p.id); await ensure_project_idle(db,p.id)
    cp=await db.get(EngineeringCheckpoint,checkpoint_id)
    if not cp or cp.project_id!=p.id: raise AppError('CHECKPOINT_NOT_FOUND','Checkpoint not found',404)
    if not cp.git_commit: raise AppError('CHECKPOINT_NOT_ROLLBACKABLE','Checkpoint has no Git commit',409)
    ok=await GitService(workspace(p,settings).root).rollback(cp.git_commit)
    if not ok: raise AppError('ROLLBACK_FAILED','Git rollback failed',500)
    await write_audit(db,request,'engineering_checkpoint_rollback',user.id,'engineering_checkpoint',str(cp.id),{'git_commit':cp.git_commit})
    await db.commit(); return MessageResponse(message='Project rolled back')

@router.post('/projects/{project_id}/code-index/rebuild')
async def rebuild_code_index(project_id:uuid.UUID,user:User=Depends(current_user),db:AsyncSession=Depends(get_session),settings:Settings=Depends(get_settings)):
    p=await owned_project(db,project_id,user); await lock_project(db,p.id); await ensure_project_idle(db,p.id)
    return await asyncio.to_thread(CodeIndex(workspace(p,settings)).rebuild)

@router.get('/projects/{project_id}/code-search')
async def code_search(project_id:uuid.UUID,q:str=Query(...,min_length=2,max_length=1000),limit:int=Query(12,ge=1,le=50),user:User=Depends(current_user),db:AsyncSession=Depends(get_session),settings:Settings=Depends(get_settings)):
    p=await owned_project(db,project_id,user)
    hits=await asyncio.to_thread(CodeIndex(workspace(p,settings)).search,q,limit)
    return [{'path':h.path,'score':h.score,'language':h.language,'symbols':h.symbols,'excerpt':h.excerpt} for h in hits]

@router.get('/projects/{project_id}/git-status')
async def git_status(project_id:uuid.UUID,user:User=Depends(current_user),db:AsyncSession=Depends(get_session),settings:Settings=Depends(get_settings)):
    p=await owned_project(db,project_id,user); return await GitService(workspace(p,settings).root).status()

@router.get('/runs/{run_id}/diff')
async def run_diff(run_id:uuid.UUID,user:User=Depends(current_user),db:AsyncSession=Depends(get_session),settings:Settings=Depends(get_settings)):
    r=await owned_run(db,run_id,user); p=await owned_project(db,r.project_id,user)
    pre=await db.scalar(select(EngineeringCheckpoint).where(EngineeringCheckpoint.run_id==r.id,EngineeringCheckpoint.label=='pre-run').order_by(EngineeringCheckpoint.created_at.asc()).limit(1))
    if not pre or not pre.git_commit: raise AppError('DIFF_UNAVAILABLE','No pre-run checkpoint is available',404)
    release=await db.scalar(select(EngineeringCheckpoint).where(EngineeringCheckpoint.run_id==r.id,EngineeringCheckpoint.label=='release').order_by(EngineeringCheckpoint.created_at.desc()).limit(1))
    target=release.git_commit if release and release.git_commit else 'HEAD'
    result=await GitService(workspace(p,settings).root).diff(pre.git_commit,target)
    if not result.get('ok'): raise AppError('DIFF_FAILED',str(result.get('error') or 'Unable to produce Git diff'),500)
    return result

@router.get('/projects/{project_id}/export')
async def export_project(project_id:uuid.UUID,user:User=Depends(current_user),db:AsyncSession=Depends(get_session),settings:Settings=Depends(get_settings)):
    p=await owned_project(db,project_id,user); ws=workspace(p,settings)
    members=list(ws.files())
    # Refuse before building anything: the previous check ran after the whole archive was
    # already materialised in memory, so it could not prevent the allocation it guarded.
    raw_bytes=sum(f.stat().st_size for f in members)
    if raw_bytes>settings.ENGINEERING_MAX_EXTRACTED_BYTES: raise AppError('EXPORT_TOO_LARGE','Project export exceeds configured size limit',413)
    spool=tempfile.SpooledTemporaryFile(max_size=8*1024*1024)
    def build():
        with zipfile.ZipFile(spool,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=6) as zf:
            for file in members:
                zf.write(file,str(file.relative_to(ws.root)).replace(os.sep,'/'))
        spool.seek(0)
    await asyncio.to_thread(build)
    def stream():
        try:
            while True:
                chunk=spool.read(1024*1024)
                if not chunk: break
                yield chunk
        finally:
            spool.close()
    safe=''.join(c if c.isalnum() or c in '-_' else '-' for c in p.name)[:80] or 'project'
    return StreamingResponse(stream(),media_type='application/zip',headers={'Content-Disposition':f'attachment; filename="{safe}.zip"'})
