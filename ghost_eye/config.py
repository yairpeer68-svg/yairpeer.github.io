"""
Configuration management.

Resolution order for any setting:  explicit env var  >  config file  >  default.
Config file lives at ~/.ghosteye/config.ini (override with GHOSTEYE_CONFIG).

Example ~/.ghosteye/config.ini
------------------------------
[settings]
threads = 20
timeout = 15
user_agent =
proxy =
verify_tls = true
"""

from __future__ import annotations

import configparser
import os
from pathlib import Path
from typing import Optional

_ENV_MAP: dict = {}

_DEFAULTS = {
    "threads": "10",
    "timeout": "15",
    "user_agent": "",
    "proxy": "",
    "verify_tls": "true",
    "wordlist": "",
}


def _config_path() -> Path:
    return Path(os.environ.get("GHOSTEYE_CONFIG",
                               Path.home() / ".ghosteye" / "config.ini"))


class Config:
    def __init__(self) -> None:
        self._cp = configparser.ConfigParser()
        self.path = _config_path()
        if self.path.exists():
            self._cp.read(self.path, encoding="utf-8")

    # ---- generic settings -------------------------------------------------
    def get(self, option: str, fallback: Optional[str] = None) -> Optional[str]:
        env = os.environ.get(f"GHOSTEYE_{option.upper()}")
        if env:
            return env
        if self._cp.has_option("settings", option):
            val = self._cp.get("settings", option)
            if val != "":
                return val
        return _DEFAULTS.get(option, fallback)

    def get_int(self, option: str, fallback: int) -> int:
        try:
            return int(self.get(option) or fallback)
        except (TypeError, ValueError):
            return fallback

    def get_bool(self, option: str, fallback: bool = True) -> bool:
        val = (self.get(option) or str(fallback)).strip().lower()
        return val in ("1", "true", "yes", "on")

    # ---- api keys ---------------------------------------------------------
    def api_key(self, name: str) -> Optional[str]:
        if name not in _ENV_MAP:
            return None
        env_name, section, option = _ENV_MAP[name]
        if os.environ.get(env_name):
            return os.environ[env_name]
        if self._cp.has_option(section, option):
            val = self._cp.get(section, option)
            return val or None
        return None

    def require(self, name: str) -> str:
        key = self.api_key(name)
        if not key:
            raise RuntimeError(
                f"missing API key '{name}'. Set it in {self.path} "
                f"under [api_keys] or via the {_ENV_MAP.get(name, ('ENV',))[0]} env var."
            )
        return key

    # ---- bootstrap a template --------------------------------------------
    def write_template(self) -> Path:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        cp = configparser.ConfigParser()
        cp["settings"] = dict(_DEFAULTS)
        with open(self.path, "w", encoding="utf-8") as fh:
            cp.write(fh)
        return self.path
