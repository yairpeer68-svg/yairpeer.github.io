"""Create tracking tickets from findings in Jira or ServiceNow (feature 60).

Turns a prioritised finding into a ready-to-file ticket. Credentials come from
the environment / config (never hard-coded):

  Jira:        JIRA_URL, JIRA_USER, JIRA_TOKEN, JIRA_PROJECT
  ServiceNow:  SERVICENOW_URL, SERVICENOW_USER, SERVICENOW_PASS

``build_ticket`` assembles the summary/description/priority; ``submit_ticket``
posts it (or, with ``dry_run=True`` or missing credentials, returns the exact
payload it *would* send so you can preview it safely). Reconnaissance/reporting
only — it files a ticket, it never touches the target.
"""

from __future__ import annotations

import base64
import json
import os
from typing import Any, Dict, Optional

_SEV_TO_JIRA = {"critical": "Highest", "high": "High", "medium": "Medium",
                "low": "Low", "info": "Lowest"}
# ServiceNow incident: impact/urgency 1(high)..3(low)
_SEV_TO_SNOW = {"critical": "1", "high": "1", "medium": "2",
                "low": "3", "info": "3"}


def _cfg(name: str, cfg: Optional[Dict[str, str]], default: str = "") -> str:
    if cfg and cfg.get(name):
        return str(cfg[name])
    return os.environ.get(name, default)


def build_ticket(finding: Dict[str, Any], target: str = "",
                 system: str = "jira",
                 cfg: Optional[Dict[str, str]] = None) -> Dict[str, Any]:
    """Assemble the ticket payload for the given tracker from a finding dict
    (module, severity, field, detail, risk_score, cve …)."""
    system = (system or "jira").lower()
    sev = str(finding.get("severity", "medium")).lower()
    module = finding.get("module", "finding")
    field = finding.get("field", "")
    detail = str(finding.get("detail", finding.get("value", "")))[:1500]
    cve = finding.get("cve", "")
    summary = f"[Ghost Eye] {sev.upper()} · {module}" + (f" · {field}" if field else "")
    if target:
        summary += f" · {target}"
    body_lines = [
        f"Target: {target}",
        f"Module: {module}",
        f"Severity: {sev}",
    ]
    if cve:
        body_lines.append(f"CVE: {cve}")
    if finding.get("risk_score") is not None:
        body_lines.append(f"Risk score: {finding.get('risk_score')}")
    body_lines += ["", "Detail:", detail, "",
                   "Filed by Ghost Eye — reconnaissance/detection only. "
                   "Authorised testing."]
    description = "\n".join(body_lines)

    if system == "servicenow":
        base = _cfg("SERVICENOW_URL", cfg).rstrip("/")
        return {
            "system": "servicenow",
            "url": f"{base}/api/now/table/incident" if base else "",
            "payload": {
                "short_description": summary,
                "description": description,
                "impact": _SEV_TO_SNOW.get(sev, "2"),
                "urgency": _SEV_TO_SNOW.get(sev, "2"),
                "category": "security",
            },
            "auth_user": _cfg("SERVICENOW_USER", cfg),
            "_auth_pass": _cfg("SERVICENOW_PASS", cfg),
        }
    # default: Jira
    base = _cfg("JIRA_URL", cfg).rstrip("/")
    project = _cfg("JIRA_PROJECT", cfg, "SEC")
    return {
        "system": "jira",
        "url": f"{base}/rest/api/2/issue" if base else "",
        "payload": {
            "fields": {
                "project": {"key": project},
                "summary": summary,
                "description": description,
                "issuetype": {"name": "Bug"},
                "priority": {"name": _SEV_TO_JIRA.get(sev, "Medium")},
                "labels": ["ghost-eye", "security", sev],
            }
        },
        "auth_user": _cfg("JIRA_USER", cfg),
        "_auth_pass": _cfg("JIRA_TOKEN", cfg),
    }


def _redacted(ticket: Dict[str, Any]) -> Dict[str, Any]:
    out = {k: v for k, v in ticket.items() if not k.startswith("_")}
    out["auth_user"] = ticket.get("auth_user", "")
    out["credentialed"] = bool(ticket.get("auth_user") and ticket.get("_auth_pass"))
    return out


def submit_ticket(finding: Dict[str, Any], target: str = "",
                  system: str = "jira", cfg: Optional[Dict[str, str]] = None,
                  dry_run: bool = False, timeout: int = 15) -> Dict[str, Any]:
    """File the ticket. With ``dry_run`` (or when the tracker URL / credentials
    are absent) it returns the payload it *would* send instead of posting —
    a safe preview. Returns {ok, system, ...}."""
    ticket = build_ticket(finding, target, system, cfg)
    if not ticket.get("url"):
        return {"ok": False, "system": ticket["system"],
                "reason": f"no {ticket['system'].upper()} URL configured",
                "preview": _redacted(ticket)}
    if dry_run or not (ticket.get("auth_user") and ticket.get("_auth_pass")):
        return {"ok": False, "system": ticket["system"], "dry_run": True,
                "reason": "dry-run / missing credentials — not sent",
                "preview": _redacted(ticket)}
    try:
        import requests  # lazy: only needed when actually filing
    except Exception:  # noqa: BLE001
        return {"ok": False, "system": ticket["system"],
                "reason": "requests not installed", "preview": _redacted(ticket)}
    token = base64.b64encode(
        f"{ticket['auth_user']}:{ticket['_auth_pass']}".encode()).decode()
    headers = {"Authorization": f"Basic {token}",
               "Content-Type": "application/json",
               "Accept": "application/json"}
    try:
        resp = requests.post(ticket["url"], headers=headers,
                             data=json.dumps(ticket["payload"]), timeout=timeout)
    except Exception as exc:  # noqa: BLE001
        return {"ok": False, "system": ticket["system"],
                "reason": f"request failed: {exc}", "preview": _redacted(ticket)}
    ok = 200 <= resp.status_code < 300
    ref = ""
    try:
        body = resp.json()
        ref = body.get("key") or body.get("number") or (
            body.get("result", {}) or {}).get("number", "")
    except Exception:  # noqa: BLE001
        pass
    return {"ok": ok, "system": ticket["system"],
            "status_code": resp.status_code, "ref": ref,
            "reason": "" if ok else f"tracker returned {resp.status_code}"}
