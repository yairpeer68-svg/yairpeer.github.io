"""Threat-intel & reputation modules (features #58-#64)."""

from __future__ import annotations

import socket
from concurrent.futures import ThreadPoolExecutor
from typing import Dict, List, Set

from ..core import (Context, Module, Result, clean_host, is_ip, register)


def _resolve_ip(host: str) -> str:
    return host if is_ip(host) else socket.gethostbyname(host)


# NOTE: the paid Shodan and Censys lookups were removed in v3.3 (they require a
# paid plan for any useful query). Their free, no-key replacements live in the
# 'passive' module: `internetdb` (Shodan InternetDB) and `ripestat` (RIPE NCC).


@register
class IpReputation(Module):
    id, name, category = "rbl", "IP reputation / DNS blacklists", "Threat Intel"
    target_kind = "host"

    _RBLS = [
        "zen.spamhaus.org", "bl.spamcop.net", "b.barracudacentral.org",
        "dnsbl.sorbs.net", "cbl.abuseat.org", "dnsbl-1.uceprotect.net",
    ]

    def run(self, target: str, ctx: Context) -> Result:
        try:
            host = clean_host(target)
            ip = _resolve_ip(host)
        except ValueError as exc:
            return self.fail(target, str(exc))
        except OSError as exc:
            return self.fail(target, f"cannot resolve: {exc}")
        if not is_ip(ip) or ":" in ip:
            return self.fail(host, "RBL check supports IPv4 only")
        rev = ".".join(reversed(ip.split(".")))
        listed: List[str] = []

        def check(rbl: str):
            try:
                socket.gethostbyname(f"{rev}.{rbl}")
                return rbl
            except OSError:
                return None

        with ThreadPoolExecutor(max_workers=len(self._RBLS)) as ex:
            for res in ex.map(check, self._RBLS):
                if res:
                    listed.append(res)
        return self.ok(ip, {"checked": len(self._RBLS), "listed_on": listed or ["clean"]})


@register
class Typosquat(Module):
    id, name, category = "typosquat", "Typosquat / phishing-domain monitor", "Threat Intel"
    target_kind = "domain"

    _TLDS = ["com", "net", "org", "co", "io", "info", "xyz", "online", "app"]

    def _permutations(self, name: str, tld: str) -> Set[str]:
        out: Set[str] = set()
        chars = "abcdefghijklmnopqrstuvwxyz0123456789-"
        # character omission
        for i in range(len(name)):
            out.add(name[:i] + name[i + 1:])
        # character replacement (adjacent-ish, simplified to all)
        for i in range(len(name)):
            for c in chars:
                out.add(name[:i] + c + name[i + 1:])
        # insertion
        for i in range(len(name) + 1):
            for c in chars:
                out.add(name[:i] + c + name[i:])
        # transposition
        for i in range(len(name) - 1):
            out.add(name[:i] + name[i + 1] + name[i] + name[i + 2:])
        # common homoglyph swaps
        for a, b in (("o", "0"), ("l", "1"), ("i", "1"), ("e", "3"), ("a", "@")):
            if a in name:
                out.add(name.replace(a, b))
        out.discard(name)
        # bound the explosion, then attach the original TLD set later
        return {f"{p}.{tld}" for p in out if 1 < len(p) <= len(name) + 2}

    def run(self, target: str, ctx: Context) -> Result:
        try:
            host = clean_host(target)
        except ValueError as exc:
            return self.fail(target, str(exc))
        parts = host.split(".")
        name, base_tld = parts[0], ".".join(parts[1:])
        candidates: Set[str] = self._permutations(name, base_tld)
        # also same name across other TLDs
        for tld in self._TLDS:
            candidates.add(f"{name}.{tld}")
        candidates.discard(host)

        registered: Dict[str, str] = {}

        def resolve(domain: str):
            try:
                return domain, socket.gethostbyname(domain)
            except OSError:
                return domain, None

        sample = sorted(candidates)[:600]  # keep it sane
        with ThreadPoolExecutor(max_workers=ctx.threads) as ex:
            for domain, ip in ex.map(resolve, sample):
                if ip:
                    registered[domain] = ip
        return self.ok(host, {
            "permutations_tested": len(sample),
            "registered_lookalikes": registered,
            "note": "registered lookalikes may be phishing - investigate the live ones",
        })
