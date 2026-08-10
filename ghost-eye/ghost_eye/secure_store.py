"""Encryption at rest (feature 79).

Encrypts sensitive values — API keys stored in the config file, and any JSON
blob you hand it — with a key derived from a passphrase (``GHOSTEYE_SECRET``)
via PBKDF2-HMAC-SHA256, sealed with Fernet (AES-128-CBC + HMAC).

Design goals:
  * **Opt-in & transparent** — with no passphrase set, nothing changes and
    values are stored as before. Set ``GHOSTEYE_SECRET`` and new writes are
    encrypted; old plaintext values still read (backward compatible).
  * **Graceful** — if `cryptography` isn't installed, ``available()`` is False
    and callers keep their plaintext path instead of crashing.

Each token carries its own random salt, so the same value encrypts differently
every time and no salt has to be stored separately.
"""

from __future__ import annotations

import base64
import json
import os
from typing import Any, Optional

_PREFIX = "enc:v1:"          # marks an encrypted value on disk
_ITERATIONS = 200_000


_AVAIL: Optional[bool] = None


def available() -> bool:
    """True if the crypto backend is importable and usable. Cached, and catches
    BaseException because a broken native binding (cryptography's Rust module)
    can raise a low-level panic that is not an ``Exception`` subclass. Probing
    once avoids repeating that failure on every key read."""
    global _AVAIL
    if _AVAIL is None:
        try:
            from cryptography.fernet import Fernet
            Fernet(Fernet.generate_key())    # exercise the backend once
            _AVAIL = True
        except BaseException:  # noqa: BLE001 - defensive capability probe
            _AVAIL = False
    return _AVAIL


def passphrase() -> str:
    """The active passphrase from the environment ('' = encryption disabled)."""
    return os.environ.get("GHOSTEYE_SECRET", "")


def enabled() -> bool:
    """Encryption is active only when a passphrase is set AND crypto is present."""
    return bool(passphrase()) and available()


def _fernet(pw: str, salt: bytes):
    from cryptography.fernet import Fernet
    from cryptography.hazmat.primitives import hashes
    from cryptography.hazmat.primitives.kdf.pbkdf2 import PBKDF2HMAC
    kdf = PBKDF2HMAC(algorithm=hashes.SHA256(), length=32, salt=salt,
                     iterations=_ITERATIONS)
    key = base64.urlsafe_b64encode(kdf.derive(pw.encode("utf-8")))
    return Fernet(key)


def is_encrypted(value: str) -> bool:
    return isinstance(value, str) and value.startswith(_PREFIX)


def encrypt(plaintext: str, pw: str = "") -> str:
    """Encrypt a string. Returns an ``enc:v1:`` token. Raises if crypto is
    unavailable or no passphrase is given — callers should check ``enabled()``."""
    pw = pw or passphrase()
    if not pw:
        raise ValueError("no passphrase (set GHOSTEYE_SECRET)")
    if not available():
        raise RuntimeError("cryptography not installed")
    salt = os.urandom(16)
    token = _fernet(pw, salt).encrypt(plaintext.encode("utf-8"))
    blob = base64.urlsafe_b64encode(salt + token).decode("ascii")
    return _PREFIX + blob


def decrypt(value: str, pw: str = "") -> Optional[str]:
    """Decrypt an ``enc:v1:`` token. A plaintext (non-prefixed) value is returned
    unchanged (backward compatibility). Returns None if decryption fails."""
    if not is_encrypted(value):
        return value            # legacy plaintext — pass through
    pw = pw or passphrase()
    if not pw or not available():
        return None
    try:
        raw = base64.urlsafe_b64decode(value[len(_PREFIX):].encode("ascii"))
        salt, token = raw[:16], raw[16:]
        return _fernet(pw, salt).decrypt(token).decode("utf-8")
    except Exception:  # noqa: BLE001
        return None


def maybe_encrypt(value: str) -> str:
    """Encrypt when enabled, else return the value unchanged."""
    if not value or not enabled() or is_encrypted(value):
        return value
    try:
        return encrypt(value)
    except Exception:  # noqa: BLE001
        return value


def maybe_decrypt(value: str) -> str:
    """Decrypt when the value is an encrypted token, else pass through. On a
    failed decrypt (wrong/absent passphrase) returns '' so a bad key never
    surfaces ciphertext as if it were the real secret."""
    if not is_encrypted(value):
        return value
    out = decrypt(value)
    return out if out is not None else ""


# --- encrypted JSON blob at rest ------------------------------------------- #
def write_json(path: str, obj: Any, pw: str = "") -> bool:
    """Write ``obj`` as an encrypted JSON file (0600). Returns True on success,
    False (and writes nothing) if encryption isn't available."""
    pw = pw or passphrase()
    if not pw or not available():
        return False
    token = encrypt(json.dumps(obj, ensure_ascii=False), pw)
    try:
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as fh:
            fh.write(token)
    except (AttributeError, OSError):
        with open(path, "w", encoding="utf-8") as fh:
            fh.write(token)
    return True


def read_json(path: str, pw: str = "") -> Any:
    """Read an encrypted JSON file written by ``write_json``. Returns None if the
    file is missing or can't be decrypted."""
    try:
        with open(path, encoding="utf-8") as fh:
            token = fh.read().strip()
    except OSError:
        return None
    plain = decrypt(token, pw)
    if plain is None:
        return None
    try:
        return json.loads(plain)
    except Exception:  # noqa: BLE001
        return None
