from __future__ import annotations
import asyncio
import os
import shutil
from dataclasses import dataclass
from pathlib import Path
import httpx
from app.core.config import Settings

@dataclass
class CommandResult:
    argv:list[str]; returncode:int; stdout:str; stderr:str; skipped:bool=False; reason:str|None=None

    @property
    def tool_missing(self)->bool:
        return self.skipped and (self.reason or '') in _MISSING_TOOL_REASONS

_MISSING_TOOL_REASONS={'tool is not installed','tool unavailable'}

DANGEROUS_BASENAMES={'rm','sudo','su','ssh','scp','curl','wget','nc','ncat','docker','kubectl','terraform','bash','sh','zsh','env','xargs','eval','chmod','chown','mount','systemctl'}

# Allow-listed interpreters can still execute arbitrary inline source. These flags turn an
# allow-listed tool into an arbitrary-code primitive, so they are refused per tool.
INLINE_CODE_FLAGS={
    'python':{'-c','--command'},
    'python3':{'-c','--command'},
    'node':{'-e','--eval','-p','--print','--require','-r'},
    'npx':{'-c','--call'},
}
# git -c / --exec-path / --upload-pack can point Git at an arbitrary helper binary.
GIT_UNSAFE_FLAGS={'-c','--exec-path','--upload-pack','--receive-pack','--config-env'}

class SafeExecutor:
    def __init__(self, settings:Settings, root:Path): self.settings=settings; self.root=root.resolve()

    def classify(self, argv:list[str])->tuple[bool,str|None]:
        if not argv or len(argv)>64: return False,'invalid command'
        exe=argv[0]; base=os.path.basename(exe); allowed=self.settings.engineering_allowed_commands
        if exe not in allowed and base not in allowed: return False,'command is not allow-listed'
        if base in DANGEROUS_BASENAMES: return False,'command is blocked by execution policy'
        for arg in argv[1:]:
            if '\x00' in arg or len(arg)>4000: return False,'invalid argument'
        inline=INLINE_CODE_FLAGS.get(base,set())
        for arg in argv[1:]:
            if arg in inline or any(arg.startswith(f'{flag}=') for flag in inline):
                return False,'inline code execution is blocked by execution policy'
        if base=='git':
            for arg in argv[1:]:
                if arg in GIT_UNSAFE_FLAGS or any(arg.startswith(f'{flag}=') for flag in GIT_UNSAFE_FLAGS):
                    return False,'git configuration override is blocked by execution policy'
        return True,None

    def _cwd(self,cwd:str|None)->Path:
        if not cwd or cwd=='.': return self.root
        rel=Path(cwd.replace('\\','/'))
        if rel.is_absolute() or '..' in rel.parts: return self.root
        candidate=(self.root/rel).resolve()
        if candidate!=self.root and self.root not in candidate.parents: return self.root
        return candidate if candidate.is_dir() else self.root

    async def _remote(self,argv:list[str],timeout:int,cwd:Path)->CommandResult:
        try:
            async with httpx.AsyncClient(timeout=timeout+10) as client:
                r=await client.post(self.settings.ENGINEERING_RUNNER_URL.rstrip('/')+'/run',headers={'Authorization':f'Bearer {self.settings.ENGINEERING_RUNNER_TOKEN}'},json={'workspace':str(self.root),'cwd':str(cwd.relative_to(self.root)).replace('\\','/') if cwd!=self.root else '.','argv':argv,'timeout':timeout})
            if r.status_code!=200: return CommandResult(argv,125,'',r.text[-4000:],True,'isolated runner rejected command')
            d=r.json(); return CommandResult(argv,int(d.get('returncode',125)),str(d.get('stdout',''))[-20000:],str(d.get('stderr',''))[-20000:],bool(d.get('skipped',False)),d.get('reason'))
        except httpx.HTTPError as exc: return CommandResult(argv,125,'','',True,f'isolated runner unavailable: {type(exc).__name__}')

    async def _local(self,argv:list[str],timeout:int,cwd:Path)->CommandResult:
        if not self.settings.ENGINEERING_ALLOW_LOCAL_EXECUTION: return CommandResult(argv,125,'','',True,'local execution disabled; isolated runner unavailable')
        exe=argv[0]
        if '/' not in exe and shutil.which(exe) is None: return CommandResult(argv,127,'','',True,'tool is not installed')
        env={'PATH':os.environ.get('PATH','/usr/local/bin:/usr/bin:/bin'),'HOME':str(cwd),'CI':'1','NO_COLOR':'1','LANG':'C.UTF-8'}
        proc=None
        try:
            proc=await asyncio.create_subprocess_exec(*argv,cwd=cwd,env=env,stdout=asyncio.subprocess.PIPE,stderr=asyncio.subprocess.PIPE)
            out,err=await asyncio.wait_for(proc.communicate(),timeout=timeout)
            return CommandResult(argv,proc.returncode if proc.returncode is not None else 125,
                                 out.decode(errors='replace')[-20000:],err.decode(errors='replace')[-20000:])
        except TimeoutError:
            if proc is not None:
                try: proc.kill(); await proc.wait()
                except Exception: pass
            return CommandResult(argv,124,'','command timed out')
        except OSError as exc:
            return CommandResult(argv,127,'',f'{type(exc).__name__}',True,'tool is not installed')

    async def run(self, argv:list[str], timeout:int|None=None, cwd:str|None=None)->CommandResult:
        ok,reason=self.classify(argv)
        if not ok: return CommandResult(argv,126,'','',True,reason)
        limit=timeout or self.settings.ENGINEERING_COMMAND_TIMEOUT_SECONDS
        target=self._cwd(cwd)
        if self.settings.ENGINEERING_RUNNER_URL: return await self._remote(argv,limit,target)
        return await self._local(argv,limit,target)
