"""Origin verification — turn a candidate IP into a proven one.

Finding addresses that *might* be the real server behind a CDN is the easy
half; every tool does it and then leaves you with a list to check by hand. The
half that matters is confirmation, and it has a clean test:

    ask the candidate IP directly for the target's site
    (an HTTP request to the IP carrying ``Host: target``)
    and compare what comes back with what the CDN serves.

If the candidate returns the same page, it is hosting that site — that is the
origin, not a guess. If it returns a default page, someone else's site, or an
error, the candidate is rejected. This turns "likely_origins" into a verdict
with evidence behind it.

Notes on the mechanics:

* The origin's certificate is usually issued for a different name (or is the
  hosting provider's default), so TLS verification is disabled **for this probe
  only**. Nothing security-relevant is decided from the response — it is
  compared, not trusted — and the plaintext fallback keeps working when the
  origin serves no TLS at all.
* Comparison is fuzzy on purpose: pages carry timestamps, CSRF tokens and
  rotating ads, so an exact-match-only test would reject real origins. Body
  similarity, title and server identity are scored together.

This probe *does* contact the candidate address. FOR AUTHORISED USE ONLY.
"""

from __future__ import annotations

import difflib
import hashlib
import re
from typing import Any, Dict, List, Optional

_TITLE = re.compile(r"<title[^>]*>(.*?)</title>", re.I | re.S)
# below this, two pages are not the same site
_SIMILAR = 0.90
_MAYBE = 0.60


def _norm_body(text: str) -> str:
    """Strip the parts of a page that legitimately change between requests, so
    a real origin isn't rejected over a CSRF token or a timestamp."""
    body = (text or "")[:200_000]
    body = re.sub(r"<script[\s\S]*?</script>", " ", body, flags=re.I)
    body = re.sub(r"<!--[\s\S]*?-->", " ", body)
    body = re.sub(r'(?:csrf|nonce|token|_token|sid|session)["\']?\s*[:=]\s*'
                  r'["\'][^"\']{6,}["\']', " ", body, flags=re.I)
    body = re.sub(r"\b\d{10,13}\b", " ", body)              # epoch stamps
    body = re.sub(r"\b[0-9a-f]{16,64}\b", " ", body, flags=re.I)   # hashes/ids
    body = re.sub(r"\s+", " ", body)
    return body.strip().lower()


def fingerprint(resp) -> Dict[str, Any]:
    """A comparable summary of one HTTP response."""
    text = getattr(resp, "text", "") or ""
    norm = _norm_body(text)
    title = ""
    match = _TITLE.search(text)
    if match:
        title = re.sub(r"\s+", " ", match.group(1)).strip().lower()[:120]
    headers = getattr(resp, "headers", {}) or {}
    return {
        "status": getattr(resp, "status_code", 0),
        "title": title,
        "server": str(headers.get("Server", ""))[:60],
        "length": len(norm),
        "body_hash": hashlib.sha256(norm.encode("utf-8", "replace")).hexdigest()[:16],
        "_norm": norm,
    }


def compare(baseline: Dict[str, Any], candidate: Dict[str, Any]) -> Dict[str, Any]:
    """Score how strongly a candidate looks like it serves the same site."""
    if not baseline or not candidate:
        return {"similarity": 0.0, "verdict": "unknown", "reasons": ["no data"]}
    reasons: List[str] = []
    if candidate["status"] in (0, 502, 503, 504):
        return {"similarity": 0.0, "verdict": "rejected",
                "reasons": [f"candidate returned {candidate['status'] or 'no response'}"]}

    if baseline["body_hash"] == candidate["body_hash"] and baseline["length"] > 0:
        similarity = 1.0
        reasons.append("identical page body")
    else:
        similarity = difflib.SequenceMatcher(
            None, baseline["_norm"][:60_000], candidate["_norm"][:60_000]).ratio()
        reasons.append(f"body similarity {similarity:.2f}")

    title_match = bool(baseline["title"]) and baseline["title"] == candidate["title"]
    if title_match:
        reasons.append(f"same <title>: {baseline['title'][:60]!r}")
    if baseline["server"] and baseline["server"] == candidate["server"]:
        reasons.append(f"same Server header: {baseline['server']}")

    if similarity >= _SIMILAR or (title_match and similarity >= _MAYBE):
        verdict = "confirmed"
    elif similarity >= _MAYBE or title_match:
        verdict = "possible"
    else:
        verdict = "rejected"
        reasons.append("page does not match the site served by the CDN")
    return {"similarity": round(similarity, 3), "verdict": verdict,
            "title_match": title_match, "reasons": reasons}


def _get(session, url: str, host: str, timeout: int):
    """Request `url` while claiming to be `host`. TLS verification is off for
    this probe only — see the module docstring."""
    return session.get(url, timeout=timeout, verify=False,
                       allow_redirects=False,
                       headers={"Host": host})


def baseline_fingerprint(session, host: str, timeout: int = 12) -> Optional[Dict[str, Any]]:
    """What the CDN serves for this site — the thing candidates are compared to."""
    for scheme in ("https", "http"):
        try:
            resp = session.get(f"{scheme}://{host}", timeout=timeout,
                               allow_redirects=True)
            if getattr(resp, "status_code", 0) and resp.status_code < 500:
                return fingerprint(resp)
        except Exception:  # noqa: BLE001
            continue
    return None


def verify_candidate(session, ip: str, host: str,
                     baseline: Dict[str, Any], timeout: int = 8) -> Dict[str, Any]:
    """Ask one candidate IP for the target's site and score the answer."""
    best: Dict[str, Any] = {"ip": ip, "verdict": "rejected", "similarity": 0.0,
                            "reasons": ["no usable response"], "scheme": None}
    for scheme in ("https", "http"):
        bracket = f"[{ip}]" if ":" in ip else ip
        try:
            resp = _get(session, f"{scheme}://{bracket}", host, timeout)
        except Exception as exc:  # noqa: BLE001
            best.setdefault("errors", []).append(f"{scheme}: {str(exc)[:60]}")
            continue
        scored = compare(baseline, fingerprint(resp))
        scored.update({"ip": ip, "scheme": scheme,
                       "status": getattr(resp, "status_code", 0)})
        if scored["similarity"] >= best.get("similarity", 0.0):
            best = scored
        if scored["verdict"] == "confirmed":
            break
    return best


def verify_origins(session, host: str, candidates: List[str],
                   timeout: int = 8, max_candidates: int = 12) -> Dict[str, Any]:
    """Verify candidate origin IPs for `host`. Returns per-candidate verdicts
    plus the confirmed set."""
    baseline = baseline_fingerprint(session, host, timeout=timeout + 4)
    if not baseline:
        return {"host": host, "verified": [], "confirmed_origins": [],
                "note": "could not fetch the site through the CDN, so there is "
                        "nothing to compare candidates against"}
    checked = [verify_candidate(session, ip, host, baseline, timeout)
               for ip in list(candidates)[:max_candidates]]
    checked.sort(key=lambda c: -c.get("similarity", 0.0))
    confirmed = [c["ip"] for c in checked if c["verdict"] == "confirmed"]
    possible = [c["ip"] for c in checked if c["verdict"] == "possible"]
    return {
        "host": host,
        "baseline": {k: v for k, v in baseline.items() if not k.startswith("_")},
        "candidates_checked": len(checked),
        "verified": [{k: v for k, v in c.items() if k != "_norm"} for c in checked],
        "confirmed_origins": confirmed,
        "possible_origins": possible,
        "note": ("confirmed origins returned the target's own page when asked "
                 "directly with a Host header — that is the real server, not a "
                 "guess" if confirmed else
                 "no candidate served the target's page; the origin is either "
                 "not in this candidate set or refuses direct requests"),
    }
