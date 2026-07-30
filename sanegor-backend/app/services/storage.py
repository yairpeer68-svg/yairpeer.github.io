"""Secure local file storage for uploads.

Design constraints:

* the client's filename never touches the filesystem — storage keys are
  derived from the content hash, which removes path traversal, null bytes and
  unicode-normalisation attacks in one step;
* files are sharded two levels deep so a directory never holds millions of
  entries;
* every file is written with owner-only permissions;
* the declared content type is verified against the file's magic bytes, so a
  ``.pdf`` that is really a script is rejected before it is stored.
"""

from __future__ import annotations

import asyncio
import hashlib
import os
import uuid
from dataclasses import dataclass
from pathlib import Path

from app.core.errors import NotFoundError, UnsupportedMediaTypeError, ValidationError
from app.core.logging import get_logger

logger = get_logger(__name__)

_DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

# (magic prefix, offset, content types the prefix may legitimately belong to)
_MAGIC_SIGNATURES: list[tuple[bytes, int, frozenset[str]]] = [
    (b"%PDF-", 0, frozenset({"application/pdf"})),
    (b"\x89PNG\r\n\x1a\n", 0, frozenset({"image/png"})),
    (b"\xff\xd8\xff", 0, frozenset({"image/jpeg", "image/jpg"})),
    # DOCX is a ZIP container, so this prefix is shared with other OOXML types.
    (b"PK\x03\x04", 0, frozenset({_DOCX_MIME})),
]


@dataclass(frozen=True, slots=True)
class StoredFile:
    """Reference to a file that was written to disk."""

    storage_key: str
    size_bytes: int
    checksum_sha256: str


def sniff_content_type(data: bytes, declared: str) -> str:
    """Verify ``declared`` against the file's magic bytes.

    Returns:
        The verified content type.

    Raises:
        UnsupportedMediaTypeError: when the bytes contradict the declaration.
    """
    declared = (declared or "").split(";")[0].strip().lower()

    for prefix, offset, allowed in _MAGIC_SIGNATURES:
        if data[offset : offset + len(prefix)] == prefix:
            if declared in allowed:
                return declared
            raise UnsupportedMediaTypeError(
                "תוכן הקובץ אינו תואם לסוג שהוצהר",
                details={"declared": declared, "detected": sorted(allowed)[0]},
            )

    # No signature matched: only plain text may legitimately be signature-less.
    if declared == "text/plain":
        try:
            data[:4096].decode("utf-8")
        except UnicodeDecodeError:
            # Windows-1255 is still common for Hebrew text files.
            try:
                data[:4096].decode("windows-1255")
            except UnicodeDecodeError as exc:
                raise UnsupportedMediaTypeError("הקובץ אינו קובץ טקסט תקין") from exc
        return declared

    raise UnsupportedMediaTypeError(
        "לא ניתן לאמת את סוג הקובץ", details={"declared": declared}
    )


def sanitise_filename(filename: str) -> str:
    """Reduce a client filename to something safe to store and display.

    The result is metadata only — it is never used to build a path.
    """
    name = Path(filename or "document").name
    name = name.replace("\x00", "").strip()
    forbidden = set('<>:"/\\|?*') | {chr(c) for c in range(32)}
    cleaned = "".join("_" if ch in forbidden else ch for ch in name)
    cleaned = cleaned.lstrip(". ") or "document"
    if len(cleaned) > 180:
        stem, dot, suffix = cleaned.rpartition(".")
        cleaned = (stem[:150] + dot + suffix[:20]) if dot else cleaned[:180]
    return cleaned


class FileStorage:
    """Content-addressed storage rooted at a single directory."""

    def __init__(self, root: str | Path) -> None:
        self._root = Path(root).resolve()
        self._root.mkdir(parents=True, exist_ok=True)

    @property
    def root(self) -> Path:
        return self._root

    def _path_for(self, storage_key: str) -> Path:
        """Resolve a key to a path, refusing anything outside the root."""
        candidate = (self._root / storage_key).resolve()
        if not candidate.is_relative_to(self._root):
            raise ValidationError("מזהה קובץ אינו תקין")
        return candidate

    async def save(self, data: bytes, *, owner_id: str, suffix: str = "") -> StoredFile:
        """Write ``data`` and return its reference.

        Keys embed the owner id so a leaked key from one account cannot be
        guessed into another's namespace, and a random component so two users
        uploading identical files do not share a path.
        """
        if not data:
            raise ValidationError("הקובץ ריק")

        checksum = hashlib.sha256(data).hexdigest()
        shard = f"{checksum[:2]}/{checksum[2:4]}"
        clean_suffix = "".join(ch for ch in suffix if ch.isalnum() or ch == ".")[:12]
        key = f"{owner_id}/{shard}/{uuid.uuid4().hex}{clean_suffix}"

        path = self._path_for(key)
        await asyncio.to_thread(self._write, path, data)
        logger.info("file_stored", key=key, bytes=len(data))
        return StoredFile(storage_key=key, size_bytes=len(data), checksum_sha256=checksum)

    @staticmethod
    def _write(path: Path, data: bytes) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        # Write to a temp file then rename, so a reader never sees a partial file.
        temporary = path.with_suffix(path.suffix + ".part")
        descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        try:
            with os.fdopen(descriptor, "wb") as handle:
                handle.write(data)
                handle.flush()
                os.fsync(handle.fileno())
            temporary.replace(path)
        except Exception:
            temporary.unlink(missing_ok=True)
            raise
        path.chmod(0o600)

    async def read(self, storage_key: str) -> bytes:
        """Read a stored file."""
        path = self._path_for(storage_key)
        if not path.is_file():
            raise NotFoundError("הקובץ לא נמצא בשרת")
        return await asyncio.to_thread(path.read_bytes)

    async def delete(self, storage_key: str) -> None:
        """Delete a stored file, ignoring one that is already gone."""
        path = self._path_for(storage_key)
        await asyncio.to_thread(lambda: path.unlink(missing_ok=True))
        logger.info("file_deleted", key=storage_key)

    async def exists(self, storage_key: str) -> bool:
        return await asyncio.to_thread(self._path_for(storage_key).is_file)
