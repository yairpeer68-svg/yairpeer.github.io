"""
Configuration + API-key management.

Resolution order for any key:  explicit env var  >  config file  >  None.
Config file lives at ~/.ghosteye/config.ini (override with GHOSTEYE_CONFIG).

Example ~/.ghosteye/config.ini
------------------------------
[settings]
threads = 20
timeout = 15
user_agent =
proxy =
verify_tls = true

[api_keys]
# Optional. Run `ghost_eye --set-keys` to fill these in interactively, or a
# scan will ask you for the key it needs and save your answer here.
virustotal =
abuseipdb =
deepseek =
"""

from __future__ import annotations

import configparser
import os
import sys
from pathlib import Path
from typing import Dict, List, Optional

# key name -> (env var, config section, config option).
_ENV_MAP = {
    "virustotal": ("VT_API_KEY", "api_keys", "virustotal"),
    "abuseipdb": ("ABUSEIPDB_API_KEY", "api_keys", "abuseipdb"),
    "deepseek": ("DEEPSEEK_API_KEY", "api_keys", "deepseek"),
}

# friendly labels shown in the interactive key prompt.
_KEY_LABELS = {
    "virustotal": "VirusTotal",
    "abuseipdb": "AbuseIPDB",
    "deepseek": "DeepSeek",
}

# module id -> api-key name it consumes (so a scan only asks for what it needs).
MODULE_KEYS = {
    "virustotal": "virustotal",
    "abuseipdb": "abuseipdb",
    "dsapi": "deepseek",
}

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

    def _write_config(self, cp: configparser.ConfigParser) -> None:
        """Write the config file with owner-only permissions — it can hold
        API keys, so it must not be world/group readable."""
        self.path.parent.mkdir(parents=True, exist_ok=True)
        # create/truncate with 0600 before writing (POSIX); no-op-safe elsewhere
        try:
            fd = os.open(self.path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
            with os.fdopen(fd, "w", encoding="utf-8") as fh:
                cp.write(fh)
        except (AttributeError, OSError):
            with open(self.path, "w", encoding="utf-8") as fh:
                cp.write(fh)
        try:
            os.chmod(self.path, 0o600)
        except OSError:
            pass

    def set_api_key(self, name: str, value: str) -> None:
        """Persist an API key to the config file (creates it if needed)."""
        if name not in _ENV_MAP:
            raise KeyError(f"unknown api key: {name}")
        _, section, option = _ENV_MAP[name]
        if not self._cp.has_section(section):
            self._cp.add_section(section)
        self._cp.set(section, option, value.strip())
        self._write_config(self._cp)

    def all_api_keys(self) -> Dict[str, str]:
        """Return every currently-known key {name: value} (env or file)."""
        return {n: v for n in _ENV_MAP if (v := self.api_key(n))}

    def interactive_setup(self, only: Optional[List[str]] = None) -> List[str]:
        """Ask the user for each API key and save what they enter.

        `only` restricts the prompt to a subset of key names. Returns the
        list of key names that were newly stored this session.
        """
        if not sys.stdin.isatty():
            return []
        names = [n for n in _ENV_MAP if (only is None or n in only)]
        saved: List[str] = []
        for name in names:
            label = _KEY_LABELS.get(name, name)
            if self.api_key(name):
                prompt = (f"[{label}] key already stored — "
                          f"press Enter to keep it, or paste a new one: ")
            else:
                prompt = (f"[{label}] do you have an API key? "
                          f"paste it, or press Enter to skip: ")
            try:
                entered = input(prompt).strip()
            except (EOFError, KeyboardInterrupt):
                print()
                break
            if entered:
                self.set_api_key(name, entered)
                saved.append(name)
        return saved

    def ensure_keys(self, needed: List[str]) -> List[str]:
        """Prompt (once, interactively) for any needed keys not yet set."""
        missing = [n for n in needed if not self.api_key(n)]
        if not missing:
            return []
        return self.interactive_setup(only=missing)

    # ---- bootstrap a template --------------------------------------------
    def write_template(self) -> Path:
        cp = configparser.ConfigParser()
        cp["settings"] = dict(_DEFAULTS)
        cp["api_keys"] = {k: "" for k in ("virustotal", "abuseipdb", "deepseek")}
        self._write_config(cp)
        return self.path
