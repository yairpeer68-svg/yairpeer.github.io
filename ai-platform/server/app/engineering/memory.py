import re
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from app.models.entities import ProjectMemory

async def remember(db:AsyncSession,project_id,kind:str,key:str,content:str,metadata:dict|None=None):
    key=key[:200]; item=await db.scalar(select(ProjectMemory).where(ProjectMemory.project_id==project_id,ProjectMemory.key==key))
    if item is None: item=ProjectMemory(project_id=project_id,kind=kind,key=key,content=content[:50000],metadata_json=metadata or {}); db.add(item)
    else: item.kind=kind; item.content=content[:50000]; item.metadata_json=metadata or {}
    return item

async def search_memory(db:AsyncSession,project_id,query:str,limit:int=8)->list[dict]:
    rows=list((await db.scalars(select(ProjectMemory).where(ProjectMemory.project_id==project_id).order_by(ProjectMemory.updated_at.desc()).limit(200))).all())
    terms={t for t in re.findall(r'[A-Za-z0-9_\-]{2,}',query.lower())}
    scored=[]
    for r in rows:
        text=(r.key+' '+r.content).lower(); score=sum(text.count(t) for t in terms)
        if score: scored.append((score,r))
    scored.sort(key=lambda x:x[0],reverse=True)
    return [{'key':r.key,'kind':r.kind,'content':r.content,'score':score} for score,r in scored[:limit]]
