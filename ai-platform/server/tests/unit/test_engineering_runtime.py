from pathlib import Path
from app.core.config import Settings
from app.engineering.executor import SafeExecutor
from app.engineering.protocol import parse_envelope
from app.engineering.workspace import Workspace
from app.engineering.security_scan import scan_workspace


def settings(tmp_path):
    return Settings(APP_ENV='test',AI_PROVIDER_MODE='mock',ENGINEERING_WORKSPACE_ROOT=str(tmp_path),JWT_SECRET='x'*64)

def test_workspace_blocks_traversal(tmp_path):
    ws=Workspace(settings(tmp_path),'u','p')
    try: ws.safe('../escape')
    except Exception: pass
    else: raise AssertionError('traversal must be rejected')

def test_agent_protocol_and_write(tmp_path):
    env=parse_envelope('{"summary":"ok","operations":[{"op":"write","path":"a.py","content":"print(1)"}],"commands":[["python","a.py"]]}')
    assert env.summary=='ok' and env.operations[0].path=='a.py'
    ws=Workspace(settings(tmp_path),'u','p'); ws.write_text('a.py',env.operations[0].content or '')
    assert ws.read_text('a.py')=='print(1)'

def test_executor_allowlist(tmp_path):
    ex=SafeExecutor(settings(tmp_path),Path(tmp_path))
    assert ex.classify(['python','-V'])[0]
    assert not ex.classify(['bash','-c','rm -rf /'])[0]

def test_security_scanner_finds_secret(tmp_path):
    ws=Workspace(settings(tmp_path),'u','p'); ws.write_text('config.py','api_key = "abcdefghijklmnopqrstuvwxyz123456"')
    out=scan_workspace(ws); assert out['high']>=1


def test_archive_rejects_git_metadata(tmp_path):
    import io
    import zipfile
    ws=Workspace(settings(tmp_path),'u','archive')
    buf=io.BytesIO()
    with zipfile.ZipFile(buf,'w') as zf:
        zf.writestr('.git/config','[core]\nrepositoryformatversion = 0\n')
    try:
        ws.import_zip(buf.getvalue())
    except Exception as exc:
        assert 'reserved' in str(exc).lower() or getattr(exc,'code','')=='ARCHIVE_RESERVED_PATH'
    else:
        raise AssertionError('archive Git metadata must be rejected')


def test_code_index_prioritizes_relevant_symbols(tmp_path):
    from app.engineering.code_index import CodeIndex
    ws=Workspace(settings(tmp_path),'u','index')
    ws.write_text('src/payment_service.py','class PaymentProcessor:\n    def refund_transaction(self, transaction_id):\n        return transaction_id\n')
    ws.write_text('src/weather.py','def current_temperature(city):\n    return city\n')
    result=CodeIndex(ws).rebuild()
    assert result['files_indexed']==2
    hits=CodeIndex(ws).search('refund payment transaction',5)
    assert hits and hits[0].path=='src/payment_service.py'


def test_build_profiles_detect_nested_projects(tmp_path):
    from app.engineering.profiles import detect_build_targets
    (tmp_path/'backend').mkdir(); (tmp_path/'backend'/'pyproject.toml').write_text('[project]\nname="x"\nversion="0.1"\n')
    (tmp_path/'web').mkdir(); (tmp_path/'web'/'package.json').write_text('{"scripts":{"test":"vitest run"}}')
    targets=detect_build_targets(tmp_path)
    found={(t.kind,t.cwd) for t in targets}
    assert ('python','backend') in found and ('node','web') in found


def test_executor_cwd_cannot_escape_workspace(tmp_path):
    ex=SafeExecutor(settings(tmp_path),Path(tmp_path))
    assert ex._cwd('../')==Path(tmp_path).resolve()


def test_dependency_intelligence_flags_insecure_source(tmp_path):
    from app.engineering.dependencies import analyze_dependencies
    ws=Workspace(settings(tmp_path),'u','deps')
    ws.write_text('package.json','{"dependencies":{"safe":"1.2.3","bad":"http://example.invalid/pkg.tgz"}}')
    out=analyze_dependencies(ws)
    assert out['dependency_count']==2
    assert out['high']>=1


async def test_git_diff_between_checkpoints(tmp_path):
    from app.engineering.git_service import GitService
    ws=Workspace(settings(tmp_path),'u','gitdiff')
    ws.write_text('a.txt','one\n')
    git=GitService(ws.root)
    first=await git.checkpoint('first')
    assert first
    ws.write_text('a.txt','two\n')
    second=await git.checkpoint('second')
    assert second
    diff=await git.diff(first,second)
    assert diff['ok'] and 'a.txt' in diff['files'] and '-one' in diff['diff'] and '+two' in diff['diff']


async def test_approval_resume_executes_only_after_decision():
    import uuid
    from types import SimpleNamespace
    from app.engineering.orchestrator import _resume_implementer_commands

    approval=SimpleNamespace(id=uuid.uuid4(),status='approved',decision_note=None)
    run=SimpleNamespace(id=uuid.uuid4(),status='waiting_approval',stage='approval')
    task=SimpleNamespace(id=uuid.uuid4(),role='implementer',status='waiting_approval',output_json={
        'commands':[{'command':['python','-V'],'status':'approval_required','approval_id':str(approval.id)}]
    })
    class Rows:
        def all(self): return [approval]
    class FakeDB:
        def __init__(self): self.added=[]
        async def scalars(self,_query): return Rows()
        async def commit(self): return None
        def add(self,item): self.added.append(item)
    class FakeExecutor:
        def __init__(self): self.calls=[]
        def classify(self,argv): return True,None
        async def run(self,argv):
            self.calls.append(argv)
            return SimpleNamespace(returncode=0,stdout='ok',stderr='',skipped=False,reason=None)
    db=FakeDB(); ex=FakeExecutor()
    assert await _resume_implementer_commands(db,run,task,ex)
    assert ex.calls==[['python','-V']]
    assert task.output_json['commands'][0]['status']=='executed'
    assert run.status=='running'


async def test_approval_resume_stays_paused_while_pending():
    import uuid
    from types import SimpleNamespace
    from app.engineering.orchestrator import _resume_implementer_commands

    approval=SimpleNamespace(id=uuid.uuid4(),status='pending',decision_note=None)
    run=SimpleNamespace(id=uuid.uuid4(),status='waiting_approval',stage='approval')
    task=SimpleNamespace(id=uuid.uuid4(),role='implementer',status='waiting_approval',output_json={})
    class Rows:
        def all(self): return [approval]
    class FakeDB:
        async def scalars(self,_query): return Rows()
        async def commit(self): return None
        def add(self,_item): return None
    class FakeExecutor:
        def classify(self,argv): return True,None
    assert not await _resume_implementer_commands(FakeDB(),run,task,FakeExecutor())
    assert run.status=='waiting_approval' and task.status=='waiting_approval'
