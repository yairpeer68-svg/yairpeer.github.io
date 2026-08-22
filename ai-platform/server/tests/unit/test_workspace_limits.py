import io
import zipfile

import pytest

from app.core.config import Settings
from app.core.errors import AppError
from app.engineering.workspace import Workspace


def settings(tmp_path, **overrides):
    base = dict(
        APP_ENV='test', AI_PROVIDER_MODE='mock', JWT_SECRET='x' * 64,
        ENGINEERING_WORKSPACE_ROOT=str(tmp_path),
    )
    base.update(overrides)
    return Settings(**base)


def _zip(members: dict[str, bytes]) -> bytes:
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, 'w', zipfile.ZIP_DEFLATED) as zf:
        for name, payload in members.items():
            zf.writestr(name, payload)
    return buf.getvalue()


def test_declared_size_cannot_bypass_the_extraction_budget(tmp_path):
    """A ZIP header's file_size is attacker controlled; the budget must count written bytes."""
    payload = b'\0' * (4 * 1024 * 1024)
    data = _zip({'bomb.bin': payload})

    # Forge a tiny declared size in the central directory, as a crafted archive would.
    with zipfile.ZipFile(io.BytesIO(data)) as probe:
        assert probe.infolist()[0].file_size == len(payload)

    ws = Workspace(settings(tmp_path, ENGINEERING_MAX_EXTRACTED_BYTES=1_000_000,
                            ENGINEERING_MAX_FILE_BYTES=1_000_000), 'u', 'bomb')
    with pytest.raises(AppError) as excinfo:
        ws.import_zip(data)
    assert excinfo.value.code in {'ARCHIVE_LIMIT', 'ARCHIVE_FILE_TOO_LARGE'}
    # A rejected archive must not leave a partially written tree behind.
    assert list(ws.root.rglob('*')) == []


def test_import_replaces_previous_contents(tmp_path):
    ws = Workspace(settings(tmp_path), 'u', 'replace')
    ws.import_zip(_zip({'old.py': b'print(1)'}))
    assert (ws.root / 'old.py').is_file()
    ws.import_zip(_zip({'new.py': b'print(2)'}))
    assert not (ws.root / 'old.py').exists()
    assert (ws.root / 'new.py').is_file()


def test_traversal_entry_is_rejected_before_any_write(tmp_path):
    ws = Workspace(settings(tmp_path), 'u', 'traverse')
    ws.write_text('keep.py', 'print(1)')
    with pytest.raises(AppError):
        ws.import_zip(_zip({'../escape.py': b'x', 'ok.py': b'y'}))
    assert (ws.root / 'keep.py').is_file()


def test_reserved_metadata_is_rejected(tmp_path):
    ws = Workspace(settings(tmp_path), 'u', 'reserved')
    with pytest.raises(AppError) as excinfo:
        ws.import_zip(_zip({'.git/config': b'[core]'}))
    assert excinfo.value.code == 'ARCHIVE_RESERVED_PATH'


def test_workspace_storage_quota_is_enforced(tmp_path):
    ws = Workspace(settings(tmp_path, ENGINEERING_MAX_WORKSPACE_BYTES=4096), 'u', 'quota')
    ws.write_text('a.txt', 'x' * 2000)
    with pytest.raises(AppError) as excinfo:
        ws.write_text('b.txt', 'y' * 3000)
    assert excinfo.value.code == 'WORKSPACE_QUOTA_EXCEEDED'


def test_overwriting_a_file_does_not_double_count_against_the_quota(tmp_path):
    ws = Workspace(settings(tmp_path, ENGINEERING_MAX_WORKSPACE_BYTES=4096), 'u', 'quota2')
    ws.write_text('a.txt', 'x' * 3000)
    ws.write_text('a.txt', 'y' * 3000)
    assert ws.read_text('a.txt') == 'y' * 3000


def test_workspace_key_cannot_traverse(tmp_path):
    with pytest.raises(AppError):
        Workspace(settings(tmp_path), 'u', '../../etc')


def test_clear_empties_the_workspace(tmp_path):
    ws = Workspace(settings(tmp_path), 'u', 'clear')
    ws.write_text('pkg/mod.py', 'print(1)')
    assert ws.usage()['files'] == 1
    ws.clear()
    assert ws.usage()['files'] == 0
