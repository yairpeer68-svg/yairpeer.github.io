from __future__ import annotations
import hashlib
import os
import shutil
import zipfile
from io import BytesIO
from pathlib import Path
from app.core.config import Settings
from app.core.errors import AppError

SKIP_DIRS={'.git','.ai-platform','.idea','.vscode','node_modules','build','.dart_tool','.gradle','__pycache__','.venv','venv','dist'}
RESERVED_ARCHIVE_DIRS={'.git','.ai-platform'}
_CHUNK=1024*1024

class Workspace:
    def __init__(self, settings: Settings, user_id: str, workspace_key: str):
        root=Path(settings.ENGINEERING_WORKSPACE_ROOT).expanduser().resolve()
        # user_id and workspace_key are server-generated (UUID / token_urlsafe), but they are
        # still validated so a future caller cannot turn them into a traversal vector.
        for part in (user_id, workspace_key):
            if not part or '/' in part or '\\' in part or part in {'.','..'} or '\x00' in part:
                raise AppError('INVALID_WORKSPACE_KEY','Invalid workspace identifier',422)
        self.root=(root/user_id/workspace_key).resolve()
        if self.root!=root and root not in self.root.parents:
            raise AppError('INVALID_WORKSPACE_KEY','Workspace escapes the configured root',422)
        self.settings=settings
        self.root.mkdir(parents=True,exist_ok=True)

    def safe(self, relative: str) -> Path:
        if not relative or '\x00' in relative: raise AppError('INVALID_PATH','Invalid workspace path',422)
        rel=Path(relative.replace('\\','/'))
        if rel.is_absolute() or '..' in rel.parts: raise AppError('INVALID_PATH','Path escapes project workspace',422)
        candidate=(self.root/rel).resolve()
        if candidate!=self.root and self.root not in candidate.parents: raise AppError('INVALID_PATH','Path escapes project workspace',422)
        return candidate

    def write_text(self, relative: str, content: str) -> int:
        raw=content.encode('utf-8')
        if len(raw)>self.settings.ENGINEERING_MAX_FILE_BYTES: raise AppError('FILE_TOO_LARGE','Generated file exceeds size limit',422)
        p=self.safe(relative)
        self._guard_free_space(len(raw), existing=p)
        p.parent.mkdir(parents=True,exist_ok=True); p.write_text(content,encoding='utf-8'); return len(raw)

    def mkdir(self, relative: str) -> None: self.safe(relative).mkdir(parents=True,exist_ok=True)

    def read_text(self, relative: str) -> str:
        p=self.safe(relative)
        if not p.is_file(): raise AppError('FILE_NOT_FOUND','File not found',404)
        if p.stat().st_size>self.settings.ENGINEERING_MAX_FILE_BYTES: raise AppError('FILE_TOO_LARGE','File exceeds readable size limit',422)
        try: return p.read_text(encoding='utf-8')
        except UnicodeDecodeError as exc: raise AppError('BINARY_FILE','Binary file cannot be opened as text',422) from exc

    def tree(self, relative: str='') -> list[dict]:
        base=self.safe(relative) if relative else self.root
        if not base.exists(): return []
        items=[]
        for p in sorted(base.iterdir(),key=lambda x:(not x.is_dir(),x.name.lower())):
            if p.name in SKIP_DIRS: continue
            items.append({'name':p.name,'path':str(p.relative_to(self.root)).replace(os.sep,'/'),'type':'dir' if p.is_dir() else 'file','size':p.stat().st_size if p.is_file() else None})
            if len(items)>=500: break
        return items

    def files(self):
        count=0
        for p in self.root.rglob('*'):
            if not p.is_file() or any(part in SKIP_DIRS for part in p.relative_to(self.root).parts): continue
            count+=1
            if count>self.settings.ENGINEERING_MAX_PROJECT_FILES: break
            yield p

    def usage(self) -> dict:
        """Current on-disk footprint of the workspace, including skipped build directories."""
        total=0; files=0
        for p in self.root.rglob('*'):
            try:
                if p.is_file() and not p.is_symlink():
                    total+=p.stat().st_size; files+=1
            except OSError:
                continue
        return {'bytes':total,'files':files}

    def _guard_free_space(self, incoming_bytes: int, existing: Path | None = None) -> None:
        limit=self.settings.ENGINEERING_MAX_WORKSPACE_BYTES
        if limit<=0: return
        replaced=0
        if existing is not None and existing.is_file():
            try: replaced=existing.stat().st_size
            except OSError: replaced=0
        if self.usage()['bytes']-replaced+incoming_bytes>limit:
            raise AppError('WORKSPACE_QUOTA_EXCEEDED','Project workspace storage quota exceeded',413)

    def clear(self) -> None:
        """Remove every workspace file. Used by replacing imports and account deletion."""
        for child in self.root.iterdir():
            if child.is_dir() and not child.is_symlink(): shutil.rmtree(child,ignore_errors=True)
            else:
                try: child.unlink()
                except OSError: pass

    def manifest_hash(self) -> str:
        h=hashlib.sha256()
        for p in sorted(self.files()):
            rel=str(p.relative_to(self.root)).replace(os.sep,'/'); h.update(rel.encode()); h.update(b'\0')
            try:
                with p.open('rb') as f:
                    for chunk in iter(lambda:f.read(_CHUNK),b''): h.update(chunk)
            except OSError: continue
        return h.hexdigest()

    def import_zip(self, data: bytes, replace: bool=True) -> dict:
        if len(data)>self.settings.ENGINEERING_MAX_ARCHIVE_BYTES: raise AppError('ARCHIVE_TOO_LARGE','Archive exceeds upload limit',413)
        try: zf=zipfile.ZipFile(BytesIO(data))
        except zipfile.BadZipFile as exc: raise AppError('INVALID_ARCHIVE','Invalid ZIP archive',422) from exc

        # Validate the whole central directory before writing anything, so a rejected
        # archive never leaves a half-extracted tree behind.
        entries=[]
        for info in zf.infolist():
            name=info.filename.replace('\\','/')
            if name.endswith('/'): continue
            parts=Path(name).parts
            if any(part in RESERVED_ARCHIVE_DIRS for part in parts):
                raise AppError('ARCHIVE_RESERVED_PATH','Archive contains reserved project metadata paths',422)
            # Unix symlink type.
            if ((info.external_attr>>16)&0o170000)==0o120000: raise AppError('ARCHIVE_SYMLINK','Archive symlinks are not accepted',422)
            self.safe(name)
            entries.append((info,name))
        if len(entries)>self.settings.ENGINEERING_MAX_PROJECT_FILES:
            raise AppError('ARCHIVE_LIMIT','Archive extraction limits exceeded',413)

        if replace: self.clear()

        # The declared file_size in the ZIP header is attacker controlled, so the ceiling is
        # enforced against bytes actually written. A decompression bomb is aborted mid-stream.
        budget=self.settings.ENGINEERING_MAX_EXTRACTED_BYTES
        workspace_limit=self.settings.ENGINEERING_MAX_WORKSPACE_BYTES
        if workspace_limit>0: budget=min(budget,workspace_limit)
        extracted=0; files=0
        try:
            with zf:
                for info,name in entries:
                    target=self.safe(name)
                    target.parent.mkdir(parents=True,exist_ok=True)
                    files+=1
                    written=0
                    with zf.open(info) as src, target.open('wb') as dst:
                        while True:
                            chunk=src.read(_CHUNK)
                            if not chunk: break
                            written+=len(chunk); extracted+=len(chunk)
                            if written>self.settings.ENGINEERING_MAX_FILE_BYTES:
                                raise AppError('ARCHIVE_FILE_TOO_LARGE',f'Archive member exceeds the per-file size limit: {name}',413)
                            if extracted>budget:
                                raise AppError('ARCHIVE_LIMIT','Archive extraction limits exceeded',413)
                            dst.write(chunk)
        except AppError:
            self.clear()
            raise
        return {'files':files,'bytes':extracted,'manifest_hash':self.manifest_hash()}
