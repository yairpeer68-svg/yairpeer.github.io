from __future__ import annotations

import asyncio
import json
import logging
import uuid
from datetime import UTC, datetime

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from app.ai.factory import build_provider
from app.ai.gateway import AIGateway
from app.core.config import get_settings
from app.db.session import get_engine
from app.engineering.code_index import CodeIndex
from app.engineering.dependencies import analyze_dependencies
from app.engineering.executor import SafeExecutor
from app.engineering.git_service import GitService
from app.engineering.memory import remember, search_memory
from app.engineering.protocol import parse_envelope
from app.engineering.quality import run_quality_gates
from app.engineering.security_scan import scan_workspace
from app.engineering.task_graph import default_graph
from app.engineering.workspace import Workspace
from app.models.entities import (
    EngineeringApproval,
    EngineeringCheckpoint,
    EngineeringEvent,
    EngineeringProject,
    EngineeringRun,
    EngineeringTask,
)
from app.services.redis_service import get_redis_service

log = logging.getLogger('engineering.orchestrator')

# An agent response is untrusted input; bound how much it can rewrite in one step.
MAX_OPERATIONS_PER_RESPONSE = 200
MAX_COMMANDS_PER_RESPONSE = 20


def now():
    return datetime.now(UTC)


async def event(db, run, typ, msg, task=None, level='info', data=None):
    db.add(EngineeringEvent(
        run_id=run.id,
        task_id=task.id if task else None,
        level=level,
        event_type=typ,
        message=msg,
        data_json=data or {},
    ))
    await db.commit()


async def ai_call(db: AsyncSession, run: EngineeringRun, task: EngineeringTask, system: str, user: str) -> str:
    settings = get_settings()
    gateway = AIGateway(db, get_redis_service(settings), settings, build_provider(settings))
    result = await gateway.chat(
        str(uuid.uuid4()), run.user_id, None,
        [{'role': 'system', 'content': system}, {'role': 'user', 'content': user}],
        settings.DEEPSEEK_MODEL, 0.15, min(settings.AI_MAX_RESPONSE_TOKENS, 4096), False,
    )
    return str(result['content'])


def repo_context(ws: Workspace, query: str, max_chars: int = 24000) -> str:
    """Return a relevance-ranked repository snapshot instead of first-files-only context."""
    chunks: list[str] = []
    total = 0
    try:
        hits = CodeIndex(ws).search(query, 14)
    except Exception:
        hits = []
    for hit in hits:
        piece = f'\n--- {hit.path} | score={hit.score} | symbols={", ".join(hit.symbols[:12])} ---\n{hit.excerpt}'
        if total + len(piece) > max_chars:
            break
        chunks.append(piece)
        total += len(piece)
    if chunks:
        return ''.join(chunks)

    for p in ws.files():
        rel = str(p.relative_to(ws.root)).replace('\\', '/')
        if p.suffix.lower() not in {'.py','.ts','.tsx','.js','.dart','.kt','.java','.md','.toml','.yaml','.yml','.json','.gradle','.kts','.txt'}:
            continue
        try:
            text = p.read_text(encoding='utf-8')
        except Exception:
            continue
        piece = f'\n--- {rel} ---\n{text[:5000]}'
        if total + len(piece) > max_chars:
            break
        chunks.append(piece)
        total += len(piece)
    return ''.join(chunks)


async def _pending_approvals(db: AsyncSession, run_id: uuid.UUID) -> int:
    return int(await db.scalar(select(func.count(EngineeringApproval.id)).where(
        EngineeringApproval.run_id == run_id,
        EngineeringApproval.status == 'pending',
    )) or 0)


async def _resume_implementer_commands(
    db: AsyncSession,
    run: EngineeringRun,
    task: EngineeringTask,
    executor: SafeExecutor,
) -> bool:
    """Resolve the command phase after the user has decided every approval.

    Returns True when the task can continue, False when it must remain paused.
    """
    approvals = list((await db.scalars(select(EngineeringApproval).where(
        EngineeringApproval.run_id == run.id,
        EngineeringApproval.task_id == task.id,
    ))).all())
    if any(a.status == 'pending' for a in approvals):
        run.status = 'waiting_approval'
        run.stage = 'approval'
        task.status = 'waiting_approval'
        await db.commit()
        return False

    by_id = {str(a.id): a for a in approvals}
    output = dict(task.output_json or {})
    resolved: list[dict] = []
    for item in output.get('commands', []):
        item = dict(item)
        if item.get('status') != 'approval_required':
            resolved.append(item)
            continue
        approval = by_id.get(str(item.get('approval_id', '')))
        if not approval:
            item['status'] = 'blocked'
            item['reason'] = 'approval record missing'
            resolved.append(item)
            continue
        if approval.status == 'rejected':
            item['status'] = 'rejected'
            item['reason'] = approval.decision_note or 'rejected by user'
            resolved.append(item)
            continue
        command = [str(x) for x in item.get('command', [])]
        ok, reason = executor.classify(command)
        if not ok:
            item['status'] = 'blocked'
            item['reason'] = reason or 'command blocked by execution policy'
            resolved.append(item)
            continue
        result = await executor.run(command)
        item.update({
            'status': 'executed' if result.returncode == 0 else 'failed',
            'returncode': result.returncode,
            'stdout': result.stdout[-4000:],
            'stderr': result.stderr[-4000:],
            'skipped': result.skipped,
            'reason': result.reason,
        })
        resolved.append(item)
    output['commands'] = resolved
    output['phase'] = 'commands_resolved'
    task.output_json = output
    task.status = 'running'
    run.status = 'running'
    run.stage = task.role
    await db.commit()
    await event(db, run, 'approval_resume', 'Approved command phase resumed', task, data={'commands': resolved})
    return True


async def _run(run_id: uuid.UUID):
    settings = get_settings()
    factory = async_sessionmaker(get_engine(), expire_on_commit=False)
    async with factory() as db:
        # Claim the run under a row lock. Without it two workers can both read 'queued'
        # and execute the same run concurrently against one workspace.
        run = await db.scalar(
            select(EngineeringRun).where(EngineeringRun.id == run_id).with_for_update(skip_locked=True)
        )
        if not run:
            return
        if run.status not in {'queued', 'waiting_approval'}:
            await db.rollback()
            return
        if run.status == 'waiting_approval' and await _pending_approvals(db, run.id):
            await db.rollback()
            return
        project = await db.get(EngineeringProject, run.project_id)
        if not project:
            return

        ws = Workspace(settings, str(run.user_id), project.workspace_key)
        git = GitService(ws.root)
        executor = SafeExecutor(settings, ws.root)
        was_started = run.started_at is not None
        run.status = 'running'
        run.started_at = run.started_at or now()
        run.error = None
        await db.commit()
        await event(db, run, 'run_resumed' if was_started else 'run_started', 'Autonomous engineering run resumed' if was_started else 'Autonomous engineering run started')

        tasks = list((await db.scalars(select(EngineeringTask).where(
            EngineeringTask.run_id == run.id
        ).order_by(EngineeringTask.sequence))).all())
        if not tasks:
            specs = default_graph(run.goal)
            for i, spec in enumerate(specs):
                db.add(EngineeringTask(
                    run_id=run.id, role=spec.role, title=spec.title, description=spec.description,
                    sequence=i, depends_on_json=list(spec.depends_on),
                ))
            await db.commit()
            tasks = list((await db.scalars(select(EngineeringTask).where(
                EngineeringTask.run_id == run.id
            ).order_by(EngineeringTask.sequence))).all())

        try:
            await git.ensure_repo()
            pre = await db.scalar(select(EngineeringCheckpoint).where(
                EngineeringCheckpoint.run_id == run.id,
                EngineeringCheckpoint.label == 'pre-run',
            ).order_by(EngineeringCheckpoint.created_at.asc()).limit(1))
            if pre is None:
                initial = await git.checkpoint(f'AI Platform pre-run {run.id}')
                pre = EngineeringCheckpoint(
                    project_id=project.id, run_id=run.id, label='pre-run',
                    git_commit=initial, manifest_hash=ws.manifest_hash(),
                )
                db.add(pre)
                await db.commit()

            plan: dict = {}
            quality: dict = {}
            security: dict = {}
            dependency: dict = {}
            repair_needed = False
            for existing in tasks:
                if existing.status == 'completed':
                    if existing.role in {'planner', 'architect'}:
                        plan[existing.role] = existing.output_json or {}
                    elif existing.role == 'tester':
                        quality = existing.output_json or {}
                    elif existing.role == 'security':
                        security = existing.output_json or {}
                    elif existing.role == 'dependency':
                        dependency = existing.output_json or {}
            if quality or security:
                repair_needed = bool(
                    (quality and not quality.get('passed', False))
                    or int(security.get('high', 0)) > 0
                    or int(dependency.get('high', 0)) > 0
                )

            for idx, task in enumerate(tasks):
                await db.refresh(run)
                if run.cancel_requested:
                    run.status = 'cancelled'; run.finished_at = now(); await db.commit()
                    await event(db, run, 'run_cancelled', 'Run cancelled by user')
                    return
                if task.status == 'completed':
                    continue

                if task.status == 'waiting_approval' and task.role == 'implementer':
                    if not await _resume_implementer_commands(db, run, task, executor):
                        return
                    task.status = 'completed'; task.finished_at = now()
                    run.progress = int((idx + 1) * 100 / max(1, len(tasks)))
                    await db.commit(); await event(db, run, 'task_completed', task.title, task, data=task.output_json)
                    continue

                # Tester, security and dependency intelligence are independent after implementation; execute them concurrently.
                if task.role == 'tester':
                    security_task = next((t for t in tasks if t.role == 'security' and t.status != 'completed'), None)
                    dependency_task = next((t for t in tasks if t.role == 'dependency' and t.status != 'completed'), None)
                    task.status = 'running'; task.started_at = task.started_at or now(); task.attempts += 1
                    run.stage = 'verification_parallel'; run.progress = int(idx * 100 / max(1, len(tasks)))
                    if security_task:
                        security_task.status = 'running'; security_task.started_at = security_task.started_at or now(); security_task.attempts += 1
                    if dependency_task:
                        dependency_task.status = 'running'; dependency_task.started_at = dependency_task.started_at or now(); dependency_task.attempts += 1
                    await db.commit()
                    await event(db, run, 'task_started', task.title, task, data={'parallel_group': 'verification'})
                    if security_task:
                        await event(db, run, 'task_started', security_task.title, security_task, data={'parallel_group': 'verification'})
                    if dependency_task:
                        await event(db, run, 'task_started', dependency_task.title, dependency_task, data={'parallel_group': 'verification'})
                    quality, security, dependency = await asyncio.gather(
                        run_quality_gates(executor),
                        asyncio.to_thread(scan_workspace, ws),
                        asyncio.to_thread(analyze_dependencies, ws),
                    )
                    task.output_json = quality; task.status = 'completed'; task.finished_at = now()
                    run.quality_json = quality
                    if security_task:
                        security_task.output_json = security; security_task.status = 'completed'; security_task.finished_at = now()
                    if dependency_task:
                        dependency_task.output_json = dependency; dependency_task.status = 'completed'; dependency_task.finished_at = now()
                    repair_needed = not quality.get('passed', False) or int(security.get('high', 0)) > 0 or int(dependency.get('high', 0)) > 0
                    completed_count = sum(1 for t in tasks if t.status == 'completed')
                    run.progress = int(completed_count * 100 / max(1, len(tasks)))
                    await db.commit()
                    await event(db, run, 'task_completed', task.title, task, data=quality)
                    if security_task:
                        await event(db, run, 'task_completed', security_task.title, security_task, data=security)
                    if dependency_task:
                        await event(db, run, 'task_completed', dependency_task.title, dependency_task, data=dependency)
                    continue
                if task.role in {'security','dependency'} and task.status == 'completed':
                    continue

                task.status = 'running'; task.started_at = task.started_at or now(); task.attempts += 1
                run.stage = task.role; run.progress = int(idx * 100 / max(1, len(tasks)))
                await db.commit(); await event(db, run, 'task_started', task.title, task)
                context = repo_context(ws, run.goal)
                memories = await search_memory(db, project.id, run.goal, 6)

                if task.role in {'planner', 'architect'}:
                    prompt = f"Goal: {run.goal}\nProject type: {project.project_type}\nRelevant memory: {json.dumps(memories, ensure_ascii=False)}\nRelevant repository context:{context}"
                    text = await ai_call(db, run, task,
                        'You are a senior software engineering agent. Return concise JSON with summary, notes, memory. Do not request secrets. Do not execute commands.',
                        prompt,
                    )
                    try:
                        env = parse_envelope(text); summary = env.summary; notes = env.notes
                    except Exception:
                        summary = text[:8000]; notes = {}
                    task.output_json = {'summary': summary, 'notes': notes}; plan[task.role] = task.output_json
                    run.plan_json = plan
                    await remember(db, project.id, task.role, f'run:{run.id}:{task.role}', summary, notes)
                    await db.commit()

                elif task.role == 'implementer':
                    system = '''You are the implementation agent in an autonomous software engineering platform. Return ONLY one JSON object: {"summary":"...","operations":[{"op":"write","path":"relative/path","content":"full file content"},{"op":"mkdir","path":"relative/path"}],"commands":[["tool","arg"]],"memory":[{"key":"...","content":"..."}],"notes":{}}. Paths must be relative. Never include secrets. Prefer minimal coherent changes. Do not delete files. Commands are suggestions and may require approval.'''
                    text = await ai_call(db, run, task, system, f"Goal: {run.goal}\nPlan: {json.dumps(plan, ensure_ascii=False)}\nRepository:{context}")
                    env = parse_envelope(text); changed: list[str] = []
                    for op in env.operations[:MAX_OPERATIONS_PER_RESPONSE]:
                        if op.op == 'mkdir': ws.mkdir(op.path); changed.append(op.path + '/')
                        elif op.op == 'write': ws.write_text(op.path, op.content or ''); changed.append(op.path)
                    command_results: list[dict] = []
                    pending = False
                    for cmd in env.commands[:MAX_COMMANDS_PER_RESPONSE]:
                        command = [str(x) for x in cmd]
                        ok, reason = executor.classify(command)
                        if not ok:
                            command_results.append({'command': command, 'status': 'blocked', 'reason': reason})
                            continue
                        if not settings.ENGINEERING_AUTO_EXECUTE_COMMANDS:
                            approval = EngineeringApproval(
                                run_id=run.id, task_id=task.id, kind='command_execution',
                                reason=f"Agent requested isolated command: {command}. Automatic command execution is disabled.",
                                requested_by_agent=task.role,
                            )
                            db.add(approval); await db.flush()
                            command_results.append({'command': command, 'status': 'approval_required', 'approval_id': str(approval.id)})
                            pending = True
                        else:
                            r = await executor.run(command)
                            command_results.append({'command': command, 'status': 'executed' if r.returncode == 0 else 'failed', 'returncode': r.returncode, 'stdout': r.stdout[-4000:], 'stderr': r.stderr[-4000:], 'skipped': r.skipped, 'reason': r.reason})
                    for m in env.memory:
                        await remember(db, project.id, 'implementation', m['key'], m['content'])
                    task.output_json = {'summary': env.summary, 'changed_files': changed, 'commands': command_results, 'phase': 'awaiting_commands' if pending else 'commands_resolved'}
                    await db.commit()
                    if pending:
                        task.status = 'waiting_approval'; run.status = 'waiting_approval'; run.stage = 'approval'
                        await db.commit()
                        await event(db, run, 'approval_required', 'Run paused until requested commands are approved or rejected', task, data={'commands': command_results})
                        return

                elif task.role == 'reviewer':
                    evidence = {'quality': quality, 'security': security, 'dependency': dependency, 'changed_manifest': ws.manifest_hash()}
                    text = await ai_call(db, run, task,
                        'Act as an independent code reviewer. Return JSON with summary and notes containing requirements_covered, risks, regressions, and recommended_fixes. Do not modify files.',
                        f"Goal:{run.goal}\nEvidence:{json.dumps(evidence, ensure_ascii=False)}\nRepository:{context}",
                    )
                    try:
                        env = parse_envelope(text); task.output_json = {'summary': env.summary, 'notes': env.notes}
                    except Exception:
                        task.output_json = {'summary': text[:8000]}
                    repair_needed = repair_needed or bool(task.output_json.get('notes', {}).get('recommended_fixes'))
                    await db.commit()

                elif task.role == 'repair':
                    attempts = 0; repairs: list[dict] = []
                    while repair_needed and attempts < settings.ENGINEERING_MAX_REPAIR_ATTEMPTS:
                        attempts += 1; run.repair_attempts = attempts; await db.commit()
                        system = '''You are a bounded repair agent. Return ONLY JSON with summary and write/mkdir operations. Fix only evidence-backed failures. Do not delete files, weaken tests, disable security controls, or add secrets.'''
                        text = await ai_call(db, run, task, system, f"Goal:{run.goal}\nQuality:{json.dumps(quality, ensure_ascii=False)}\nSecurity:{json.dumps(security, ensure_ascii=False)}\nRepository:{repo_context(ws, run.goal)}")
                        env = parse_envelope(text); repaired: list[str] = []
                        for op in env.operations[:MAX_OPERATIONS_PER_RESPONSE]:
                            if op.op == 'mkdir': ws.mkdir(op.path); repaired.append(op.path + '/')
                            elif op.op == 'write': ws.write_text(op.path, op.content or ''); repaired.append(op.path)
                        quality, security, dependency = await asyncio.gather(
                            run_quality_gates(executor),
                            asyncio.to_thread(scan_workspace, ws),
                            asyncio.to_thread(analyze_dependencies, ws),
                        )
                        repair_needed = not quality.get('passed', False) or int(security.get('high', 0)) > 0 or int(dependency.get('high', 0)) > 0
                        repairs.append({'attempt': attempts, 'summary': env.summary, 'changed_files': repaired, 'quality_passed': quality.get('passed'), 'security_high': security.get('high'), 'dependency_high': dependency.get('high')})
                    task.output_json = {'attempts': repairs, 'resolved': not repair_needed}; run.quality_json = quality
                    await db.commit()

                elif task.role == 'qa':
                    passed = not repair_needed and quality.get('passed', True) and int(security.get('high', 0)) == 0 and int(dependency.get('high', 0)) == 0
                    task.output_json = {'accepted': passed, 'quality': quality, 'security': security, 'dependency': dependency, 'goal': run.goal}
                    await db.commit()
                    if not passed:
                        raise RuntimeError('Acceptance verification failed after bounded repair attempts')

                elif task.role == 'release':
                    commit = await git.checkpoint(f'AI Platform run {run.id}: {run.goal[:100]}')
                    manifest = ws.manifest_hash()
                    index_result = CodeIndex(ws).rebuild()
                    diff = await git.diff(pre.git_commit, commit or 'HEAD') if pre and pre.git_commit else {'ok': False, 'files': [], 'diff': '', 'truncated': False}
                    db.add(EngineeringCheckpoint(
                        project_id=project.id, run_id=run.id, label='release', git_commit=commit,
                        manifest_hash=manifest, metadata_json={'changed_files': diff.get('files', []), 'index': index_result},
                    ))
                    task.output_json = {
                        'git_commit': commit, 'manifest_hash': manifest,
                        'changed_files': diff.get('files', []), 'diff_truncated': diff.get('truncated', False),
                        'code_index': index_result,
                    }
                    await db.commit()

                task.status = 'completed'; task.finished_at = now()
                run.progress = int((idx + 1) * 100 / max(1, len(tasks)))
                await db.commit(); await event(db, run, 'task_completed', task.title, task, data=task.output_json)

            run.status = 'completed'; run.stage = 'done'; run.progress = 100; run.finished_at = now()
            await db.commit(); await event(db, run, 'run_completed', 'Run completed successfully', data={'quality': run.quality_json})
        except Exception as exc:
            await db.rollback(); run = await db.get(EngineeringRun, run_id)
            if run:
                error = f'{type(exc).__name__}: {str(exc)[:2000]}'
                active_tasks = list((await db.scalars(select(EngineeringTask).where(
                    EngineeringTask.run_id == run.id,
                    EngineeringTask.status == 'running',
                ))).all())
                for active_task in active_tasks:
                    active_task.status = 'failed'; active_task.finished_at = now()
                    payload = dict(active_task.output_json or {}); payload['error'] = error; active_task.output_json = payload
                run.status = 'failed'; run.error = error; run.finished_at = now()
                await db.commit(); await event(db, run, 'run_failed', 'Engineering run failed safely', level='error', data={'error': run.error})
            log.exception('engineering_run_failed')


async def _mark_timed_out(run_id: uuid.UUID) -> None:
    factory = async_sessionmaker(get_engine(), expire_on_commit=False)
    async with factory() as db:
        run = await db.get(EngineeringRun, run_id)
        if not run or run.status in {'completed', 'failed', 'cancelled'}:
            return
        run.status = 'failed'
        run.error = 'RunTimeout: the run exceeded ENGINEERING_RUN_TIMEOUT_SECONDS'
        run.finished_at = now()
        for task in (await db.scalars(select(EngineeringTask).where(
            EngineeringTask.run_id == run.id, EngineeringTask.status == 'running',
        ))).all():
            task.status = 'failed'
            task.finished_at = now()
        await db.commit()
        await event(db, run, 'run_failed', 'Engineering run exceeded its time budget', level='error')


async def run_engineering_run(run_id: str | uuid.UUID):
    identifier = uuid.UUID(str(run_id))
    budget = get_settings().ENGINEERING_RUN_TIMEOUT_SECONDS
    try:
        if budget > 0:
            await asyncio.wait_for(_run(identifier), timeout=budget)
        else:
            await _run(identifier)
    except TimeoutError:
        log.error('engineering_run_timeout', extra={'event': 'RunTimeout'})
        await _mark_timed_out(identifier)
