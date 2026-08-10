"""Data-driven OSINT at scale.

The counterpart to the many hand-written single-source modules: these read a
*registry* (see ``ghost_eye.registry_data``) and check a username or email
against hundreds — or, with an external Sherlock/WhatsMyName dataset, thousands
— of sources in one pass. Findings feed the entity graph like any other module.

Quality is data-driven too: every "present" verdict is re-checked against a
random canary username to drop sites that answer 200 for *any* name, and each
hit carries a confidence derived from how it was detected.

Passive detection only — a public profile URL is fetched and classified. No
account is created, no message is sent.
FOR AUTHORISED / OSINT USE ONLY.
"""

from __future__ import annotations

import hashlib
import re
import secrets
from concurrent.futures import ThreadPoolExecutor, as_completed
from typing import Dict, List, Optional, Tuple

from ..core import Context, Module, Result, register
from ..registry_data import Site, load_sites, registry_stats

_USERNAME_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{1,39}$")
# a subset used by the fast variant checker and as the canary target set
_TOP = ["GitHub", "Instagram", "Twitter/X", "Reddit", "TikTok", "GitLab",
        "Medium", "Steam", "Twitch", "Telegram"]


def _clean_username(raw: str) -> str:
    return (raw or "").strip().lstrip("@").strip()


def _classify(site: Site, resp) -> Tuple[str, str]:
    """(verdict, confidence) where verdict is present|absent|unknown."""
    status = getattr(resp, "status_code", 0)
    body = ""
    if site.check == "message":
        try:
            body = (resp.text or "")[:200_000].lower()
        except Exception:  # noqa: BLE001
            body = ""
    if site.check == "status":
        if status == site.absent_code:
            return "absent", "high"
        if status == site.present_code:
            return "present", "high"
        return "unknown", "low"
    if site.check == "message":
        if site.present_strings and any(s.lower() in body for s in site.present_strings):
            return "present", "high"
        if site.absent_strings and any(s.lower() in body for s in site.absent_strings):
            return "absent", "high"
        if status == site.present_code:
            return "present", "medium"      # 200 with no absence marker
        if status == site.absent_code:
            return "absent", "high"
        return "unknown", "low"
    if site.check == "redirect":
        history = getattr(resp, "history", []) or []
        final = str(getattr(resp, "url", "") or "")
        if status == site.absent_code:
            return "absent", "high"
        if history and (final.rstrip("/").count("/") <= 3):
            return "absent", "medium"       # redirected away to a root/landing
        if status == site.present_code:
            return "present", "medium"
        return "unknown", "low"
    return "unknown", "low"


def _probe(ctx: Context, site: Site, username: str) -> Optional[dict]:
    if not site.username_ok(username):
        return None
    url = site.build(username)
    try:
        resp = ctx.session.get(url, timeout=ctx.timeout, allow_redirects=True)
    except Exception:  # noqa: BLE001 - one dead site never fails the sweep
        return None
    verdict, confidence = _classify(site, resp)
    if verdict != "present":
        return None
    return {"name": site.name, "cat": site.category, "url": url,
            "confidence": confidence, "check": site.check}


@register
class UsernameScan(Module):
    id, name, category = "usernamescan", "Username enumeration at scale (data-driven)", "OSINT"
    target_kind = "host"          # the target is a username

    def run(self, target: str, ctx: Context) -> Result:
        username = _clean_username(target)
        if not _USERNAME_RE.match(username):
            return self.fail(target, "not a plausible username "
                             "(letters/digits . _ - , 2-40 chars)")
        sites = load_sites("username")
        if not sites:
            return self.fail(username, "username registry is empty")
        # a cap keeps a huge external dataset bounded; override with the config
        # key 'username_max' (0 = no cap)
        cap = 500
        try:
            cap = int(ctx.config.get("username_max") or 500)  # type: ignore[union-attr]
        except Exception:  # noqa: BLE001
            pass
        if cap and len(sites) > cap:
            sites = sites[:cap]
        workers = max(4, min(getattr(ctx, "threads", 10) * 2, 40))
        hits: List[dict] = []
        with ThreadPoolExecutor(max_workers=workers) as ex:
            futs = {ex.submit(_probe, ctx, s, username): s for s in sites}
            for fut in as_completed(futs):
                try:
                    r = fut.result()
                except Exception:  # noqa: BLE001
                    r = None
                if r:
                    hits.append(r)
        # --- false-positive / placeholder guard (features 6, 75-77) ---------
        # re-check every hit against a random canary; a site that also "finds"
        # the canary answers 200 for everyone and is not real signal.
        canary = "ge" + secrets.token_hex(6)
        by_name = {s.name: s for s in sites}
        unreliable = set()
        suspect = [h for h in hits]
        with ThreadPoolExecutor(max_workers=workers) as ex:
            futs = {ex.submit(_probe, ctx, by_name[h["name"]], canary): h["name"]
                    for h in suspect if h["name"] in by_name}
            for fut in as_completed(futs):
                try:
                    if fut.result():           # canary "found" here => bogus
                        unreliable.add(futs[fut])
                except Exception:  # noqa: BLE001
                    pass
        confirmed = [h for h in hits if h["name"] not in unreliable]
        confirmed.sort(key=lambda h: (0 if h["confidence"] == "high" else 1,
                                      h["name"].lower()))
        by_cat: Dict[str, List[str]] = {}
        for h in confirmed:
            by_cat.setdefault(h["cat"], []).append(h["name"])
        env_wide = len(unreliable) >= max(5, len(hits) * 0.8)
        return self.ok(username, {
            "username": username,
            "sites_checked": len(sites),
            "found_on": [{"site": h["name"], "url": h["url"],
                          "confidence": h["confidence"]} for h in confirmed],
            "found_count": len(confirmed),
            "by_category": {k: sorted(v) for k, v in sorted(by_cat.items())},
            "profile_urls": [h["url"] for h in confirmed],
            "dropped_as_false_positive": sorted(unreliable) or "none",
            "note": ("many sites returned 200 for a random canary too — this "
                     "network/environment likely intercepts requests, treat "
                     "results with care" if env_wide else
                     "each hit was confirmed against a random-username canary; "
                     "verify high-value matches manually"),
        })


@register
class UsernameVariants(Module):
    id, name, category = "usernamevariants", "Username variant generation + check", "OSINT"
    target_kind = "host"

    _LEET = str.maketrans({"a": "4", "e": "3", "i": "1", "o": "0", "s": "5"})
    _SUFFIXES = ["", "1", "01", "123", "_", ".", "official", "real", "hq",
                 "dev", "io", "app", "tv", "yt"]

    def _variants(self, base: str) -> List[str]:
        out = {base}
        # separator swaps for multi-part names
        if any(c in base for c in "._-"):
            core = re.split(r"[._-]", base)
            for sep in ("", ".", "_", "-"):
                out.add(sep.join(p for p in core if p))
        # leetspeak
        out.add(base.translate(self._LEET))
        # common suffixes
        for suf in self._SUFFIXES:
            if suf:
                out.add(base + suf)
        # trimmed to plausible usernames, bounded
        cleaned = [v for v in out if v and v != base and _USERNAME_RE.match(v)]
        return [base] + sorted(cleaned)[:24]

    def run(self, target: str, ctx: Context) -> Result:
        base = _clean_username(target)
        if not _USERNAME_RE.match(base):
            return self.fail(target, "not a plausible username")
        variants = self._variants(base)
        sites = [s for s in load_sites("username") if s.name in _TOP]
        if not sites:
            sites = load_sites("username")[:8]
        workers = max(4, min(getattr(ctx, "threads", 10) * 2, 30))
        found: Dict[str, List[str]] = {}
        with ThreadPoolExecutor(max_workers=workers) as ex:
            futs = {ex.submit(_probe, ctx, s, v): (v, s.name)
                    for v in variants for s in sites}
            for fut in as_completed(futs):
                v, sname = futs[fut]
                try:
                    if fut.result():
                        found.setdefault(v, []).append(sname)
                except Exception:  # noqa: BLE001
                    pass
        return self.ok(base, {
            "base": base,
            "variants_generated": variants,
            "variant_count": len(variants),
            "checked_on": sorted(s.name for s in sites),
            "variants_found": {v: sorted(s) for v, s in sorted(found.items())} or "none",
            "note": "candidate handles the same person may also use — confirm "
                    "ownership before acting on any match."})


@register
class EmailFootprint(Module):
    id, name, category = "emailfootprint", "Email footprint (Gravatar/Libravatar)", "OSINT"
    target_kind = "host"          # the target is an email address

    _EMAIL_RE = re.compile(r"^[^@\s]+@[^@\s]+\.[^@\s]+$")

    def _hash(self, email: str) -> str:
        return hashlib.md5(email.strip().lower().encode("utf-8")).hexdigest()

    def _gravatar(self, ctx: Context, h: str) -> dict:
        out: Dict[str, object] = {}
        try:
            r = ctx.session.get(f"https://www.gravatar.com/{h}.json",
                                timeout=ctx.timeout, allow_redirects=True)
            if r.status_code == 200:
                data = r.json()
                entry = (data.get("entry") or [{}])[0]
                out["display_name"] = entry.get("displayName") or entry.get("preferredUsername")
                out["location"] = entry.get("currentLocation")
                accounts = entry.get("accounts") or []
                out["linked_accounts"] = [
                    {"service": a.get("shortname") or a.get("domain"),
                     "username": a.get("username"),
                     "url": a.get("url")} for a in accounts if isinstance(a, dict)]
                out["about"] = entry.get("aboutMe")
                urls = entry.get("urls") or []
                out["links"] = [u.get("value") for u in urls if isinstance(u, dict)]
                out["profile"] = f"https://gravatar.com/{h}"
        except Exception:  # noqa: BLE001
            pass
        return {k: v for k, v in out.items() if v}

    def _avatar_exists(self, ctx: Context, base: str, h: str) -> bool:
        try:
            r = ctx.session.get(f"{base}/avatar/{h}?d=404",
                                timeout=ctx.timeout, allow_redirects=True)
            return getattr(r, "status_code", 0) == 200
        except Exception:  # noqa: BLE001
            return False

    def run(self, target: str, ctx: Context) -> Result:
        email = (target or "").strip().lower()
        if not self._EMAIL_RE.match(email):
            return self.fail(target, "not a valid email address")
        h = self._hash(email)
        grav = self._gravatar(ctx, h)
        has_gravatar = self._avatar_exists(ctx, "https://www.gravatar.com", h)
        has_libravatar = self._avatar_exists(ctx, "https://seccdn.libravatar.org", h)
        signals = sum([bool(grav), has_gravatar, has_libravatar])
        return self.ok(email, {
            "email": email,
            "md5_hash": h,
            "gravatar_avatar": has_gravatar,
            "libravatar_avatar": has_libravatar,
            "gravatar_profile": grav or "none",
            "linked_accounts": grav.get("linked_accounts", "none"),
            "footprint_signals": signals,
            "note": "non-intrusive email OSINT — public avatar/profile lookups by "
                    "email hash. No message was sent to the address. Gravatar's "
                    "linked accounts are self-declared by the owner."})


# make the registry stats importable for the dashboard/CLI banner
def username_registry_stats() -> dict:
    return registry_stats("username")
