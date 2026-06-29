"""CVE correlation (new). Takes the product+version fingerprints exposed by a
target (Server / X-Powered-By headers, TLS, JS libraries) and cross-references
the public NVD database to surface *prioritised* known CVEs - turning a bare
'nginx 1.18.0' into actual advisories.

NVD keyword search is used (no API key). It is a keyword match, not strict CPE
matching, so treat the result as leads to verify, not proof of exploitability.
Detection only - no exploitation."""

from __future__ import annotations

import re
import time
from typing import Dict, List

from ..core import Context, Module, Result, clean_host, ensure_scheme, register

_NVD = "https://services.nvd.nist.gov/rest/json/cves/2.0"
_VER_RE = re.compile(r"([A-Za-z][A-Za-z0-9_\-]{1,30})[/ ]([0-9]+\.[0-9][0-9A-Za-z.\-]*)")
_IGNORE = {"http", "https", "text", "html", "charset", "utf", "close", "keep"}


def _extract_products(server: str, powered: str, extra: str = "") -> List[str]:
    blob = " ".join(x for x in (server, powered, extra) if x)
    out: List[str] = []
    for name, ver in _VER_RE.findall(blob):
        if name.lower() in _IGNORE:
            continue
        token = f"{name} {ver}"
        if token not in out:
            out.append(token)
    return out[:4]


def _parse_nvd(data: dict, limit: int = 6) -> List[dict]:
    cves = []
    for item in data.get("vulnerabilities", [])[:limit]:
        cve = item.get("cve", {})
        score, sev = None, None
        metrics = cve.get("metrics", {})
        for key in ("cvssMetricV31", "cvssMetricV30", "cvssMetricV2"):
            if metrics.get(key):
                d = metrics[key][0].get("cvssData", {})
                score = d.get("baseScore")
                sev = d.get("baseSeverity") or metrics[key][0].get("baseSeverity")
                break
        desc = ""
        for d in cve.get("descriptions", []):
            if d.get("lang") == "en":
                desc = d.get("value", "")[:160]
                break
        cves.append({"id": cve.get("id"), "cvss": score,
                     "severity": sev or "?", "summary": desc})
    cves.sort(key=lambda c: c["cvss"] or 0, reverse=True)
    return cves


@register
class CveLookup(Module):
    id = "cve"
    name = "CVE correlation (NVD)"
    category = "Threat Intel"
    target_kind = "url"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as exc:
            return self.fail(target, str(exc))
        # 1) fingerprint product/version cheaply from headers
        try:
            r = ctx.session.get(ensure_scheme(host), timeout=ctx.timeout)
        except Exception as exc:  # noqa: BLE001
            return self.fail(host, f"request failed: {exc}")
        products = _extract_products(r.headers.get("Server", ""),
                                     r.headers.get("X-Powered-By", ""),
                                     r.headers.get("X-Generator", ""))
        if not products:
            return self.ok(host, {"products": "none disclosed in headers",
                                  "note": "no Server/X-Powered-By version to match; "
                                          "try the 'tech' and 'jslibs' modules first"})
        # 2) query NVD per product (rate-limited; unauth NVD is ~5 req / 30s)
        findings: Dict[str, object] = {}
        for i, prod in enumerate(products[:3]):
            if i:
                time.sleep(1.5)
            try:
                resp = ctx.session.get(_NVD, params={"keywordSearch": prod,
                                                     "resultsPerPage": 6},
                                       timeout=ctx.timeout + 15)
                if resp.status_code != 200:
                    findings[prod] = f"NVD HTTP {resp.status_code}"
                    continue
                cves = _parse_nvd(resp.json())
                findings[prod] = cves or "no CVEs matched"
            except Exception as exc:  # noqa: BLE001
                findings[prod] = f"lookup failed: {str(exc)[:50]}"
        return self.ok(host, {"products_detected": products, "cves": findings,
                              "note": "keyword match - verify against the exact "
                                      "version before acting"})
