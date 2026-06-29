"""WHOIS / RDAP modules (features #6, #7 + classic whois)."""

from __future__ import annotations

from datetime import datetime, timezone
from typing import Dict

from ..core import (Context, Module, Result, clean_host, have_binary,
                    register, run_cmd)


@register
class Whois(Module):
    id, name, category = "whois", "WHOIS lookup", "DNS"
    target_kind = "host"

    def run(self, target: str, ctx: Context) -> Result:
        try:
            host = clean_host(target)
        except ValueError as exc:
            return self.fail(target, str(exc))
        # Prefer python-whois; fall back to the system binary.
        try:
            import whois as pywhois
            w = pywhois.whois(host)
            data = {k: (v.isoformat() if isinstance(v, datetime) else v)
                    for k, v in dict(w).items() if v}
            if data:
                return self.ok(host, data)
        except ImportError:
            pass
        except Exception:
            pass
        if have_binary("whois"):
            out = run_cmd(["whois", host], timeout=ctx.timeout + 15)
            return self.ok(host, {"raw": out.splitlines()})
        return self.fail(host, "install python-whois (pip) or the 'whois' binary")


@register
class DomainAge(Module):
    id, name, category = "age", "Domain age / WHOIS dates", "DNS"
    target_kind = "domain"
    needs = ["python-whois"]

    def run(self, target: str, ctx: Context) -> Result:
        try:
            host = clean_host(target)
            import whois as pywhois
        except ImportError:
            return self.fail(target, "requires python-whois")
        except ValueError as exc:
            return self.fail(target, str(exc))
        try:
            w = pywhois.whois(host)
        except Exception as exc:  # noqa: BLE001
            return self.fail(host, f"whois failed: {exc}")

        def first(val):
            return val[0] if isinstance(val, list) and val else val

        created = first(w.creation_date)
        expires = first(w.expiration_date)
        updated = first(w.updated_date)
        data: Dict[str, object] = {
            "registrar": w.registrar,
            "created": created.isoformat() if isinstance(created, datetime) else created,
            "expires": expires.isoformat() if isinstance(expires, datetime) else expires,
            "updated": updated.isoformat() if isinstance(updated, datetime) else updated,
        }
        if isinstance(created, datetime):
            if created.tzinfo is None:
                created = created.replace(tzinfo=timezone.utc)
            age_days = (datetime.now(timezone.utc) - created).days
            data["age_days"] = age_days
            data["age_years"] = round(age_days / 365.25, 2)
            data["note"] = ("very young domain (<30d) - common in phishing"
                            if age_days < 30 else "")
        return self.ok(host, data)


@register
class Rdap(Module):
    id, name, category = "rdap", "RDAP lookup (modern WHOIS)", "DNS"
    target_kind = "host"

    def run(self, target: str, ctx: Context) -> Result:
        try:
            host = clean_host(target)
        except ValueError as exc:
            return self.fail(target, str(exc))
        sess = ctx.session
        from ..core import is_ip
        url = (f"https://rdap.org/ip/{host}" if is_ip(host)
               else f"https://rdap.org/domain/{host}")
        try:
            resp = sess.get(url, timeout=ctx.timeout,
                            headers={"Accept": "application/rdap+json"})
            if resp.status_code != 200:
                return self.fail(host, f"RDAP HTTP {resp.status_code}")
            j = resp.json()
            events = {e.get("eventAction"): e.get("eventDate")
                      for e in j.get("events", [])}
            entities = []
            for ent in j.get("entities", []):
                roles = ent.get("roles", [])
                entities.append({"handle": ent.get("handle"), "roles": roles})
            return self.ok(host, {
                "handle": j.get("handle"),
                "name": j.get("name") or j.get("ldhName"),
                "status": j.get("status"),
                "events": events,
                "entities": entities[:8],
                "nameservers": [ns.get("ldhName") for ns in j.get("nameservers", [])],
            })
        except Exception as exc:  # noqa: BLE001
            return self.fail(host, f"RDAP failed: {exc}")
