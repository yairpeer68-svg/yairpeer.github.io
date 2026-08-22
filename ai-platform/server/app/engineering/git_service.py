import asyncio
import re
import shutil
from pathlib import Path

_HEX_COMMIT = re.compile(r'^[0-9a-fA-F]{7,64}$')

class GitService:
    def __init__(self,root:Path): self.root=root
    async def _run(self,*args):
        if shutil.which('git') is None: return None
        p=await asyncio.create_subprocess_exec('git',*args,cwd=self.root,stdout=asyncio.subprocess.PIPE,stderr=asyncio.subprocess.PIPE)
        out,err=await p.communicate(); return p.returncode,out.decode(errors='replace').strip(),err.decode(errors='replace').strip()
    async def ensure_repo(self):
        if not (self.root/'.git').exists():
            await self._run('init'); await self._run('config','user.email','ai-platform@localhost'); await self._run('config','user.name','AI Platform')
        # Imported repositories are untrusted. Never execute repository-local hooks.
        await self._run('config','core.hooksPath','/dev/null')
    async def checkpoint(self,label:str)->str|None:
        await self.ensure_repo(); await self._run('add','-A'); await self._run('commit','--allow-empty','-m',label[:180]); r=await self._run('rev-parse','HEAD'); return r[1] if r and r[0]==0 else None
    async def rollback(self,commit:str)->bool:
        if not _HEX_COMMIT.fullmatch(commit): return False
        r=await self._run('reset','--hard',commit); return bool(r and r[0]==0)
    async def current_commit(self)->str|None:
        r=await self._run('rev-parse','HEAD'); return r[1] if r and r[0]==0 else None
    async def diff(self,from_commit:str,to_commit:str='HEAD',max_chars:int=200_000)->dict:
        if not _HEX_COMMIT.fullmatch(from_commit): return {'ok':False,'error':'invalid commit','diff':'','files':[],'truncated':False}
        if to_commit!='HEAD' and not _HEX_COMMIT.fullmatch(to_commit): return {'ok':False,'error':'invalid target commit','diff':'','files':[],'truncated':False}
        names=await self._run('diff','--name-only',from_commit,to_commit,'--','.')
        patch=await self._run('diff','--no-ext-diff','--unified=3',from_commit,to_commit,'--','.')
        if not patch or patch[0]!=0: return {'ok':False,'error':patch[2] if patch else 'git unavailable','diff':'','files':[],'truncated':False}
        text=patch[1]; truncated=len(text)>max_chars
        return {'ok':True,'error':None,'diff':text[:max_chars],'files':[x for x in (names[1].splitlines() if names and names[0]==0 else []) if x],'truncated':truncated}
    async def status(self)->dict:
        r=await self._run('status','--porcelain=v1')
        lines=r[1].splitlines() if r and r[0]==0 else []
        return {'clean':not lines,'entries':lines[:500]}
