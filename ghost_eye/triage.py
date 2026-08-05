"""Triage / acknowledgement store (noise reduction).

Lets an analyst mark a surfaced item (a subdomain, IP, service, CVE, leak…) as
*known / accepted* for a target, so change-alert monitoring stops re-alerting on
it. Persisted to a small 0600 JSON file (``~/.ghosteye/acks.json`` or
``$GHOSTEYE_ACKS``). Local only — nothing leaves the machine.
"""

from __future__ import annotations

import json
import os
from pathlib import Path
from typing import Dict, List


def _path() -> Path:
    return Path(os.environ.get("GHOSTEYE_ACKS",
                               Path.home() / ".ghosteye" / "acks.json"))


def _load() -> Dict[str, List[str]]:
    p = _path()
    try:
        return json.loads(p.read_text(encoding="utf-8")) if p.exists() else {}
    except Exception:  # noqa: BLE001 - a corrupt file must not break scans
        return {}


def _save(data: Dict[str, List[str]]) -> None:
    p = _path()
    p.parent.mkdir(parents=True, exist_ok=True)
    blob = json.dumps(data, indent=1).encode("utf-8")
    # write 0600 so acknowledgements aren't world-readable
    fd = os.open(str(p), os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    try:
        os.write(fd, blob)
    finally:
        os.close(fd)


def list_acks(target: str) -> List[str]:
    return sorted(_load().get(target, []))


def ack(target: str, item: str, add: bool = True) -> List[str]:
    """Acknowledge (add=True) or un-acknowledge (add=False) an item for a target.
    Returns the updated acknowledgement list."""
    item = (item or "").strip()
    if not target or not item:
        return list_acks(target)
    data = _load()
    current = set(data.get(target, []))
    if add:
        current.add(item)
    else:
        current.discard(item)
    data[target] = sorted(current)
    if not data[target]:
        data.pop(target, None)
    _save(data)
    return sorted(current)


def filter_new(target: str, items: List[str]) -> List[str]:
    """Drop already-acknowledged items from a list of newly-seen ones."""
    acks = set(_load().get(target, []))
    return [x for x in (items or []) if x not in acks]
