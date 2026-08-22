import asyncio
import os
import resource
import secrets
import signal
from pathlib import Path
from fastapi import FastAPI, Header, HTTPException
from pydantic import BaseModel, Field

TOKEN=os.environ.get('RUNNER_TOKEN','')
ROOT=Path(os.environ.get('WORKSPACE_ROOT','/workspaces')).resolve()
ALLOWED={x.strip() for x in os.environ.get('RUNNER_ALLOWED_COMMANDS','python,python3,pytest,ruff,mypy,git,npm,npx,node,dart,flutter,gradle,./gradlew').split(',') if x.strip()}
MAX_TIMEOUT=int(os.environ.get('RUNNER_MAX_TIMEOUT','300'))
sem=asyncio.Semaphore(int(os.environ.get('RUNNER_CONCURRENCY','2')))
app=FastAPI(title='AI Platform Isolated Runner',docs_url=None,redoc_url=None,openapi_url=None)
class RunIn(BaseModel):
    workspace:str=Field(max_length=2000); cwd:str=Field(default='.',max_length=1000); argv:list[str]=Field(min_length=1,max_length=64); timeout:int=Field(default=180,ge=1,le=600)

def auth(value):
    # Constant-time comparison: a plain != leaks the token prefix through response timing.
    if not TOKEN or not value or not secrets.compare_digest(value, f'Bearer {TOKEN}'):
        raise HTTPException(401,'unauthorized')
def workspace_path(raw):
    p=Path(raw).resolve()
    if p!=ROOT and ROOT not in p.parents: raise HTTPException(422,'workspace outside runner root')
    if not p.is_dir(): raise HTTPException(404,'workspace not found')
    return p
def cwd_path(workspace:Path,raw:str):
    rel=Path(raw.replace('\\','/'))
    if rel.is_absolute() or '..' in rel.parts: raise HTTPException(422,'invalid cwd')
    p=(workspace/rel).resolve()
    if p!=workspace and workspace not in p.parents: raise HTTPException(422,'cwd outside workspace')
    if not p.is_dir(): raise HTTPException(404,'cwd not found')
    return p
BLOCKED={'sudo','su','ssh','scp','curl','wget','nc','ncat','docker','kubectl','terraform','rm','bash','sh','zsh','env','xargs','chmod','chown','mount','systemctl'}
# Allow-listed interpreters remain arbitrary-code primitives when handed inline source.
INLINE_CODE_FLAGS={'python':{'-c','--command'},'python3':{'-c','--command'},
                   'node':{'-e','--eval','-p','--print','-r','--require'},'npx':{'-c','--call'}}
GIT_UNSAFE_FLAGS={'-c','--exec-path','--upload-pack','--receive-pack','--config-env'}

def classify(argv):
    exe=argv[0]; base=os.path.basename(exe)
    if exe not in ALLOWED and base not in ALLOWED: raise HTTPException(422,'command not allowed')
    if base in BLOCKED: raise HTTPException(422,'command blocked')
    if any('\x00' in x or len(x)>4000 for x in argv): raise HTTPException(422,'invalid argument')
    inline=INLINE_CODE_FLAGS.get(base,set())
    for arg in argv[1:]:
        if arg in inline or any(arg.startswith(f'{f}=') for f in inline):
            raise HTTPException(422,'inline code execution blocked')
    if base=='git':
        for arg in argv[1:]:
            if arg in GIT_UNSAFE_FLAGS or any(arg.startswith(f'{f}=') for f in GIT_UNSAFE_FLAGS):
                raise HTTPException(422,'git configuration override blocked')
MEMORY_LIMIT_BYTES=int(os.environ.get('RUNNER_MEMORY_LIMIT_BYTES', str(3*1024**3)))
CPU_SECONDS=int(os.environ.get('RUNNER_CPU_SECONDS','600'))
FSIZE_BYTES=int(os.environ.get('RUNNER_MAX_FILE_BYTES', str(512*1024**2)))

def limits():
    resource.setrlimit(resource.RLIMIT_NOFILE,(1024,1024))
    resource.setrlimit(resource.RLIMIT_NPROC,(256,256))
    resource.setrlimit(resource.RLIMIT_CORE,(0,0))
    resource.setrlimit(resource.RLIMIT_FSIZE,(FSIZE_BYTES,FSIZE_BYTES))
    try: resource.setrlimit(resource.RLIMIT_CPU,(CPU_SECONDS,CPU_SECONDS))
    except Exception: pass
    # RLIMIT_AS is not applied to JVM/Dart toolchains: they reserve large virtual address
    # ranges they never touch, and the hard cap made gradle/flutter fail spuriously.
    # The container memory limit is the real ceiling.
    if MEMORY_LIMIT_BYTES>0:
        try: resource.setrlimit(resource.RLIMIT_AS,(MEMORY_LIMIT_BYTES,MEMORY_LIMIT_BYTES))
        except Exception: pass
    os.setsid()

@app.get('/health')
async def health(): return {'status':'ok','concurrency':sem._value,'allowed_tools':sorted(ALLOWED)}
@app.post('/run')
async def run(payload:RunIn,authorization:str|None=Header(default=None)):
    auth(authorization); classify(payload.argv); workspace=workspace_path(payload.workspace); cwd=cwd_path(workspace,payload.cwd); timeout=min(payload.timeout,MAX_TIMEOUT)
    env={'PATH':os.environ.get('PATH','/usr/local/bin:/usr/bin:/bin'),'HOME':str(cwd),'CI':'1','NO_COLOR':'1','LANG':'C.UTF-8'}
    async with sem:
        proc=None
        try:
            proc=await asyncio.create_subprocess_exec(*payload.argv,cwd=cwd,env=env,stdout=asyncio.subprocess.PIPE,stderr=asyncio.subprocess.PIPE,preexec_fn=limits)
            out,err=await asyncio.wait_for(proc.communicate(),timeout=timeout)
            return {'returncode':proc.returncode,'stdout':out.decode(errors='replace')[-20000:],'stderr':err.decode(errors='replace')[-20000:]}
        except TimeoutError:
            if proc is not None:
                # The child runs in its own session, so kill the whole process group:
                # proc.kill() alone leaves build daemons (gradle, dart) running.
                try: os.killpg(proc.pid, signal.SIGKILL)
                except Exception:
                    try: proc.kill()
                    except Exception: pass
                try: await proc.wait()
                except Exception: pass
            return {'returncode':124,'stdout':'','stderr':'command timed out','skipped':False,'reason':'timeout'}
        except OSError as exc: return {'returncode':127,'stdout':'','stderr':f'{type(exc).__name__}: command unavailable','skipped':True,'reason':'tool unavailable'}
