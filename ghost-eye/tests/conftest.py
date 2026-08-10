"""Shared test isolation.

The suite used to construct a real `Config()`, which reads the *developer's*
~/.ghosteye/config.ini — so results depended on whose machine it ran on, and a
test that saved a key would have written to a real home directory. Point every
Ghost Eye path at a throwaway directory for the whole session instead.
"""

from __future__ import annotations

import pytest


@pytest.fixture(autouse=True, scope="session")
def _isolate_ghosteye_home(tmp_path_factory):
    home = tmp_path_factory.mktemp("ghosteye-home")
    import os
    env = {
        "GHOSTEYE_CONFIG": str(home / "config.ini"),
        "GHOSTEYE_ERRORLOG": str(home / "errors.log"),
        "GHOSTEYE_ALERT_RULES": str(home / "alert_rules.json"),
        "GHOSTEYE_NO_KEYRING": "1",   # never touch the OS secret store
    }
    saved = {k: os.environ.get(k) for k in env}
    os.environ.update(env)
    # a stray key in the environment would change what modules do
    for leaked in ("VT_API_KEY", "ABUSEIPDB_API_KEY", "DEEPSEEK_API_KEY",
                   "GHOSTEYE_SECRET", "GHOSTEYE_TOKEN"):
        saved[leaked] = os.environ.pop(leaked, None)
    yield home
    for key, val in saved.items():
        if val is None:
            os.environ.pop(key, None)
        else:
            os.environ[key] = val
