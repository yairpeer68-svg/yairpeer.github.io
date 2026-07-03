"""Advanced DNS modules (v3.5 features #1-#12). Detection only."""

from __future__ import annotations

import hashlib
import re
import socket
from concurrent.futures import ThreadPoolExecutor
from typing import Dict, List

from ..core import Context, Module, Result, clean_host, register


def _resolver(ctx: Context):
    import dns.resolver
    r = dns.resolver.Resolver()
    r.lifetime = ctx.timeout
    r.timeout = ctx.timeout
    return r


@register
class DnssecChain(Module):
    id, name, category = "dnssecchain", "DNSSEC full chain validation", "DNS"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
            import dns.resolver
        except ImportError:
            return self.fail(target, "requires dnspython")
        except ValueError as e:
            return self.fail(target, str(e))
        r = _resolver(ctx)
        chain = {}
        issues = []
        for rtype in ("DS", "DNSKEY", "RRSIG"):
            try:
                ans = r.resolve(host, rtype)
                chain[rtype] = [str(rr)[:120] for rr in ans]
            except Exception:
                chain[rtype] = []
        if not chain["DNSKEY"]:
            issues.append("no DNSKEY - DNSSEC not enabled")
        if not chain["DS"]:
            issues.append("no DS record at parent - chain broken")
        if chain["DNSKEY"] and not chain["RRSIG"]:
            issues.append("DNSKEY present but no RRSIG - signatures missing")
        if chain["DS"] and chain["DNSKEY"] and chain["RRSIG"]:
            try:
                ds_algos = set()
                for ds in chain["DS"]:
                    parts = ds.split()
                    if len(parts) >= 3:
                        ds_algos.add(int(parts[2]))
                if 1 in ds_algos:
                    issues.append("DS uses SHA-1 (algorithm 1) - consider SHA-256")
            except Exception:
                pass
        grade = "pass" if not issues else "partial" if chain.get("DNSKEY") else "fail"
        return self.ok(host, {"chain": chain, "issues": issues or ["full chain valid"],
                              "grade": grade})


@register
class DnsWildcard(Module):
    id, name, category = "dnswildcard", "DNS wildcard detection (advanced)", "DNS"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        probes = [f"ghosteye-wild-{hashlib.md5(host.encode()).hexdigest()[:8]}.{host}",
                  f"nxdomain-test-{hash(host) & 0xffff}.{host}",
                  f"zz-nonexistent-abc123.{host}"]
        results = {}
        for fqdn in probes:
            for qtype in ("A", "AAAA", "CNAME"):
                try:
                    import dns.resolver
                    r = _resolver(ctx)
                    ans = r.resolve(fqdn, qtype)
                    results[f"{fqdn}/{qtype}"] = [str(rr) for rr in ans]
                except ImportError:
                    try:
                        if qtype == "A":
                            results[f"{fqdn}/A"] = [socket.gethostbyname(fqdn)]
                    except OSError:
                        pass
                    break
                except Exception:
                    continue
        wildcard = bool(results)
        ips = set()
        for vals in results.values():
            ips.update(vals)
        return self.ok(host, {"wildcard": wildcard,
                              "wildcard_targets": sorted(ips) if ips else "none",
                              "probes": results or "all NXDOMAIN (no wildcard)",
                              "impact": "subdomain enumeration results will include false positives"
                              if wildcard else "clean - no wildcard interference"})


@register
class DomainAge(Module):
    id, name, category = "domainage", "Domain age & registrar history", "DNS"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
            import whois as pywhois
        except ImportError:
            return self.fail(target, "requires python-whois")
        except ValueError as e:
            return self.fail(target, str(e))
        try:
            w = pywhois.whois(host)
        except Exception as exc:
            return self.fail(host, f"whois failed: {exc}")
        created = w.creation_date
        if isinstance(created, list):
            created = created[0]
        updated = w.updated_date
        if isinstance(updated, list):
            updated = updated[0]
        expires = w.expiration_date
        if isinstance(expires, list):
            expires = expires[0]
        from datetime import datetime, timezone
        age_days = None
        if created:
            try:
                if not created.tzinfo:
                    created = created.replace(tzinfo=timezone.utc)
                age_days = (datetime.now(timezone.utc) - created).days
            except Exception:
                pass
        registrar = w.registrar
        return self.ok(host, {
            "created": str(created) if created else "unknown",
            "updated": str(updated) if updated else "unknown",
            "expires": str(expires) if expires else "unknown",
            "age_days": age_days,
            "age_years": round(age_days / 365.25, 1) if age_days else None,
            "registrar": registrar or "unknown",
            "name_servers": w.name_servers if w.name_servers else [],
            "note": "very young domains (<30 days) are suspicious"
            if age_days and age_days < 30 else ""})


@register
class SubdomainTakeover(Module):
    id, name, category = "subtakeover", "Subdomain takeover detection", "DNS"
    target_kind = "domain"

    _FINGERPRINTS = {
        "s3.amazonaws.com": "NoSuchBucket",
        "herokuapp.com": "No such app",
        "github.io": "There isn't a GitHub Pages site here",
        "azurewebsites.net": "404 Web Site not found",
        "cloudfront.net": "Bad request",
        "pantheon.io": "404 error unknown site",
        "shopify.com": "Sorry, this shop is currently unavailable",
        "tumblr.com": "There's nothing here",
        "wpengine.com": "The site you were looking for couldn't be found",
        "ghost.io": "The thing you were looking for is no longer here",
        "surge.sh": "project not found",
        "bitbucket.io": "Repository not found",
        "fastly.net": "Fastly error: unknown domain",
        "zendesk.com": "Help Center Closed",
        "readme.io": "Project doesnt exist",
        "cargo.site": "If you're moving your domain away",
        "feedpress.me": "The feed has not been found",
        "freshdesk.com": "There is no helpdesk here",
        "helpjuice.com": "We could not find what you're looking for",
        "helpscout.net": "No settings were found for this company",
        "unbouncepages.com": "The requested URL was not found",
    }

    def run(self, target, ctx):
        try:
            host = clean_host(target)
            import dns.resolver
        except ImportError:
            return self.fail(target, "requires dnspython")
        except ValueError as e:
            return self.fail(target, str(e))
        r = _resolver(ctx)
        from .subdomains import SubdomainEnum
        subs_result = SubdomainEnum().run(host, ctx)
        subs = list(subs_result.data.get("subdomains", {}).keys()) if subs_result.status == "ok" else []
        candidates = [host, f"www.{host}"] + subs[:100]
        vulnerable = []
        checked = 0
        for sub in dict.fromkeys(candidates):
            try:
                answers = r.resolve(sub, "CNAME")
                cname = str(answers[0].target).rstrip(".")
            except Exception:
                continue
            checked += 1
            service = None
            for svc_domain in self._FINGERPRINTS:
                if svc_domain in cname:
                    service = svc_domain
                    break
            if not service:
                continue
            try:
                resp = ctx.session.get(f"http://{sub}", timeout=ctx.timeout, verify=False)
                fp = self._FINGERPRINTS[service]
                if fp.lower() in resp.text.lower():
                    vulnerable.append({"subdomain": sub, "cname": cname,
                                       "service": service, "status": "VULNERABLE"})
                else:
                    vulnerable.append({"subdomain": sub, "cname": cname,
                                       "service": service, "status": "claimed"})
            except Exception:
                try:
                    socket.gethostbyname(cname)
                except OSError:
                    vulnerable.append({"subdomain": sub, "cname": cname,
                                       "service": service,
                                       "status": "DANGLING (CNAME unresolvable)"})
        return self.ok(host, {"checked_cnames": checked,
                              "findings": vulnerable or "no takeover candidates found"})


@register
class DnsPropagation(Module):
    id, name, category = "dnsprop", "DNS propagation checker", "DNS"
    target_kind = "domain"

    _RESOLVERS = {
        "Google": "8.8.8.8",
        "Cloudflare": "1.1.1.1",
        "Quad9": "9.9.9.9",
        "OpenDNS": "208.67.222.222",
        "AdGuard": "94.140.14.14",
        "Comodo": "8.26.56.26",
    }

    def run(self, target, ctx):
        try:
            host = clean_host(target)
            import dns.resolver
        except ImportError:
            return self.fail(target, "requires dnspython")
        except ValueError as e:
            return self.fail(target, str(e))
        results = {}
        all_ips = set()
        for name, server in self._RESOLVERS.items():
            r = dns.resolver.Resolver()
            r.nameservers = [server]
            r.lifetime = ctx.timeout
            r.timeout = ctx.timeout
            try:
                ans = r.resolve(host, "A")
                ips = sorted(str(rr) for rr in ans)
                results[name] = {"server": server, "answers": ips}
                all_ips.update(ips)
            except Exception as exc:
                results[name] = {"server": server, "answers": [], "error": str(exc)[:60]}
        consistent = len(all_ips) <= 1 or all(
            set(r.get("answers", [])) == all_ips for r in results.values() if r.get("answers"))
        return self.ok(host, {"resolvers": results, "consistent": consistent,
                              "unique_ips": sorted(all_ips),
                              "note": "" if consistent else
                              "inconsistent answers - DNS may be propagating or geo-balanced"})


@register
class DmarcDkimSpfAudit(Module):
    id, name, category = "dmarcalign", "DMARC/DKIM/SPF alignment audit", "DNS"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
            import dns.resolver
        except ImportError:
            return self.fail(target, "requires dnspython")
        except ValueError as e:
            return self.fail(target, str(e))
        r = _resolver(ctx)
        issues = []
        data = {}
        # SPF
        try:
            txts = [b"".join(rr.strings).decode() for rr in r.resolve(host, "TXT")]
            spf = [t for t in txts if t.startswith("v=spf1")]
            data["spf"] = spf[0] if spf else None
            if not spf:
                issues.append("no SPF record")
            elif "+all" in spf[0]:
                issues.append("SPF uses +all (allows any sender)")
            elif "~all" in spf[0]:
                issues.append("SPF uses ~all (soft fail) - consider -all")
        except Exception:
            data["spf"] = None
            issues.append("SPF lookup failed")
        # DMARC
        try:
            txts = [b"".join(rr.strings).decode() for rr in r.resolve(f"_dmarc.{host}", "TXT")]
            dmarc = [t for t in txts if "v=DMARC1" in t]
            data["dmarc"] = dmarc[0] if dmarc else None
            if not dmarc:
                issues.append("no DMARC record")
            else:
                if "p=none" in dmarc[0]:
                    issues.append("DMARC policy is 'none' (monitoring only)")
                if "p=reject" not in dmarc[0] and "p=quarantine" not in dmarc[0]:
                    issues.append("DMARC not enforcing (recommend p=quarantine or p=reject)")
        except Exception:
            data["dmarc"] = None
            issues.append("DMARC lookup failed")
        # DKIM (common selectors)
        selectors = ["default", "google", "selector1", "selector2", "k1", "dkim", "mail"]
        dkim_found = []
        for sel in selectors:
            try:
                txts = [b"".join(rr.strings).decode() for rr in
                        r.resolve(f"{sel}._domainkey.{host}", "TXT")]
                if any("v=DKIM1" in t or "p=" in t for t in txts):
                    dkim_found.append(sel)
            except Exception:
                continue
        data["dkim_selectors"] = dkim_found or "none found"
        if not dkim_found:
            issues.append("no DKIM selector found (checked common selectors)")
        # alignment
        aligned = data["spf"] and data["dmarc"] and dkim_found
        grade = "A" if aligned and not issues else "B" if aligned else "C" if data["spf"] else "F"
        return self.ok(host, {**data, "issues": issues or ["all aligned"],
                              "grade": grade})


@register
class PassiveDnsHistory(Module):
    id, name, category = "pdns", "Passive DNS history", "DNS"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        records = []
        # SecurityTrails community / VirusTotal passive DNS (free)
        try:
            r = ctx.session.get(
                f"https://otx.alienvault.com/api/v1/indicators/domain/{host}/passive_dns",
                timeout=ctx.timeout + 10,
                headers={"User-Agent": "GhostEye"})
            if r.status_code == 200:
                for entry in r.json().get("passive_dns", [])[:50]:
                    records.append({
                        "hostname": entry.get("hostname"),
                        "address": entry.get("address"),
                        "type": entry.get("record_type"),
                        "first_seen": entry.get("first"),
                        "last_seen": entry.get("last"),
                    })
        except Exception:
            pass
        # Fallback: HackerTarget
        if not records:
            try:
                r = ctx.session.get(
                    f"https://api.hackertarget.com/hostsearch/?q={host}",
                    timeout=ctx.timeout)
                if r.status_code == 200 and "," in r.text:
                    for line in r.text.strip().splitlines()[:50]:
                        parts = line.split(",")
                        if len(parts) >= 2:
                            records.append({"hostname": parts[0], "address": parts[1],
                                            "source": "hackertarget"})
            except Exception:
                pass
        return self.ok(host, {"records": records or "no passive DNS data found",
                              "count": len(records)})


@register
class DnsRebinding(Module):
    id, name, category = "dnsrebind", "DNS rebinding vulnerability check", "DNS"
    target_kind = "domain"

    _PRIVATE_RANGES = ["10.", "172.16.", "172.17.", "172.18.", "172.19.",
                       "172.20.", "172.21.", "172.22.", "172.23.", "172.24.",
                       "172.25.", "172.26.", "172.27.", "172.28.", "172.29.",
                       "172.30.", "172.31.", "192.168.", "127.", "0."]

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        findings = {}
        try:
            import dns.resolver
            r = _resolver(ctx)
            ans = r.resolve(host, "A")
            ips = [str(rr) for rr in ans]
            ttls = [ans.rrset.ttl] if ans.rrset else []
            findings["ips"] = ips
            findings["ttl"] = ttls[0] if ttls else "unknown"
            private = [ip for ip in ips
                       if any(ip.startswith(p) for p in self._PRIVATE_RANGES)]
            findings["resolves_to_private"] = private or "none"
            if private:
                findings["risk"] = "HIGH - domain resolves to private IP (DNS rebinding indicator)"
            elif ttls and ttls[0] < 10:
                findings["risk"] = "MEDIUM - very low TTL (<10s) could enable DNS rebinding"
            else:
                findings["risk"] = "LOW"
        except ImportError:
            try:
                ip = socket.gethostbyname(host)
                findings["ip"] = ip
                private = any(ip.startswith(p) for p in self._PRIVATE_RANGES)
                findings["resolves_to_private"] = ip if private else "no"
                findings["risk"] = "HIGH - resolves to private IP" if private else "LOW"
            except OSError as exc:
                return self.fail(host, str(exc))
        except Exception as exc:
            return self.fail(host, str(exc))
        return self.ok(host, findings)


@register
class TyposquatFinder(Module):
    id, name, category = "homoglyph", "Homoglyph / typosquat domain finder", "DNS"
    target_kind = "domain"

    _HOMOGLYPHS = {
        'a': ['á', 'ä', 'à', 'å', 'ą'],
        'e': ['é', 'ë', 'è', 'ę', 'ě'],
        'i': ['í', 'ì', 'ï', 'î'],
        'o': ['ó', 'ö', 'ò', 'ő', '0'],
        'u': ['ú', 'ü', 'ù', 'ű'],
        'l': ['1', 'í'],
        's': ['$', '5'],
        'g': ['9', 'q'],
        'n': ['ñ'],
    }

    def _generate_typos(self, domain: str) -> List[str]:
        name = domain.split(".")[0]
        tld = ".".join(domain.split(".")[1:])
        typos = set()
        # character swap
        for i in range(len(name) - 1):
            t = name[:i] + name[i + 1] + name[i] + name[i + 2:]
            typos.add(f"{t}.{tld}")
        # missing character
        for i in range(len(name)):
            t = name[:i] + name[i + 1:]
            if t:
                typos.add(f"{t}.{tld}")
        # double character
        for i in range(len(name)):
            t = name[:i] + name[i] * 2 + name[i + 1:]
            typos.add(f"{t}.{tld}")
        # adjacent key (simplified)
        for i in range(len(name)):
            for c in self._HOMOGLYPHS.get(name[i], [])[:2]:
                t = name[:i] + c + name[i + 1:]
                typos.add(f"{t}.{tld}")
        # wrong TLD
        for alt_tld in ("com", "net", "org", "io", "co"):
            if alt_tld != tld:
                typos.add(f"{name}.{alt_tld}")
        # dash variations
        for i in range(1, len(name)):
            typos.add(f"{name[:i]}-{name[i:]}.{tld}")
        return sorted(typos)[:80]

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        typos = self._generate_typos(host)
        registered = {}

        def check(domain):
            try:
                ip = socket.gethostbyname(domain)
                return domain, ip
            except OSError:
                return domain, None

        with ThreadPoolExecutor(max_workers=ctx.threads * 2) as ex:
            for domain, ip in ex.map(check, typos):
                if ip:
                    registered[domain] = ip
        return self.ok(host, {"typosquats_registered": registered or "none found",
                              "checked": len(typos),
                              "note": "registered look-alike domains may be phishing"})


@register
class NsDelegation(Module):
    id, name, category = "nsdelegation", "NS delegation consistency", "DNS"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
            import dns.resolver
        except ImportError:
            return self.fail(target, "requires dnspython")
        except ValueError as e:
            return self.fail(target, str(e))
        r = _resolver(ctx)
        data = {}
        issues = []
        # child NS
        try:
            child_ns = sorted(str(rr.target).rstrip(".") for rr in r.resolve(host, "NS"))
            data["child_ns"] = child_ns
        except Exception:
            data["child_ns"] = []
            issues.append("cannot resolve NS from child zone")
        # parent NS (query parent zone)
        parent = ".".join(host.split(".")[1:])
        try:
            parent_ns_servers = [str(rr.target).rstrip(".") for rr in r.resolve(parent, "NS")]
            data["parent_zone"] = parent
            data["parent_ns_servers"] = parent_ns_servers
        except Exception:
            issues.append("cannot query parent zone NS")
        # check each NS is resolvable
        for ns in data.get("child_ns", []):
            try:
                socket.gethostbyname(ns)
            except OSError:
                issues.append(f"NS {ns} does not resolve (broken delegation)")
        # check all NS return the same SOA serial
        serials = {}
        for ns in data.get("child_ns", [])[:4]:
            try:
                ns_r = dns.resolver.Resolver()
                ns_r.nameservers = [socket.gethostbyname(ns)]
                ns_r.lifetime = ctx.timeout
                soa = ns_r.resolve(host, "SOA")
                serials[ns] = soa[0].serial
            except Exception:
                serials[ns] = "failed"
        data["soa_serials"] = serials
        if len(set(v for v in serials.values() if v != "failed")) > 1:
            issues.append("SOA serial mismatch between nameservers (zone not synced)")
        return self.ok(host, {**data, "issues": issues or ["delegation consistent"]})


@register
class DomainExpiry(Module):
    id, name, category = "domexpiry", "Domain expiration monitor", "DNS"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
            import whois as pywhois
        except ImportError:
            return self.fail(target, "requires python-whois")
        except ValueError as e:
            return self.fail(target, str(e))
        try:
            w = pywhois.whois(host)
        except Exception as exc:
            return self.fail(host, f"whois failed: {exc}")
        expires = w.expiration_date
        if isinstance(expires, list):
            expires = expires[0]
        from datetime import datetime, timezone
        days_left = None
        if expires:
            try:
                if not expires.tzinfo:
                    expires = expires.replace(tzinfo=timezone.utc)
                days_left = (expires - datetime.now(timezone.utc)).days
            except Exception:
                pass
        level = "ok"
        if days_left is not None:
            if days_left < 0:
                level = "EXPIRED"
            elif days_left < 7:
                level = "critical"
            elif days_left < 30:
                level = "warning"
            elif days_left < 90:
                level = "attention"
        return self.ok(host, {"expires": str(expires) if expires else "unknown",
                              "days_left": days_left, "alert": level,
                              "registrar": w.registrar or "unknown"})


@register
class GlueRecordAudit(Module):
    id, name, category = "glue", "Glue record audit", "DNS"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
            import dns.resolver
        except ImportError:
            return self.fail(target, "requires dnspython")
        except ValueError as e:
            return self.fail(target, str(e))
        r = _resolver(ctx)
        try:
            ns_records = [str(rr.target).rstrip(".") for rr in r.resolve(host, "NS")]
        except Exception:
            return self.fail(host, "cannot resolve NS records")
        glue_info = {}
        issues = []
        for ns in ns_records:
            if ns.endswith(f".{host}") or ns == host:
                # in-bailiwick NS requires glue
                try:
                    ips = [str(rr) for rr in r.resolve(ns, "A")]
                    glue_info[ns] = {"ips": ips, "in_bailiwick": True, "glue_required": True}
                except Exception:
                    glue_info[ns] = {"ips": [], "in_bailiwick": True, "glue_required": True}
                    issues.append(f"in-bailiwick NS {ns} has no A record (missing glue)")
            else:
                try:
                    ips = [str(rr) for rr in r.resolve(ns, "A")]
                    glue_info[ns] = {"ips": ips, "in_bailiwick": False}
                except Exception:
                    glue_info[ns] = {"ips": [], "in_bailiwick": False}
                    issues.append(f"NS {ns} does not resolve")
        return self.ok(host, {"nameservers": glue_info,
                              "issues": issues or ["glue records OK"]})
