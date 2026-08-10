"""Infrastructure attribution — which assets are run by the same operator.

Naive tooling says "these two hosts share a nameserver, therefore they're
related". That is almost always wrong: a million sites share Cloudflare's
nameservers. What makes shared evidence meaningful is its **selectivity** — how
few things in the world carry that exact value.

This engine scores attribution properly:

1. **Extraction** — pull pivotable identifiers out of whatever the 550 modules
   produced (analytics/tag IDs, certificate serial & public-key hashes, favicon
   hashes, JARM/JA3, MX/NS sets, ASN, S3 buckets, …), keyed by host. It reads
   the *flattened* result data, so it works regardless of which module emitted
   a value or what it named the field.

2. **Selectivity weighting** — each evidence *type* has a prior (a shared
   certificate serial is near-proof; a shared ASN is nearly meaningless), and
   each *value* is then re-weighted by its inverse frequency in the observed
   corpus. A value that shows up on every host carries no information and is
   driven to zero automatically — so shared-infrastructure noise (Cloudflare,
   AWS, Google) is demoted by the data itself rather than by a hard-coded
   blocklist, which can never be complete.

3. **Fusion** — independent evidence is combined with noisy-OR,
   ``P = 1 - Π(1 - wᵢ)``, so several weak signals can add up while no single
   weak signal can carry a link on its own.

4. **Clustering** — hosts linked above a confidence threshold are grouped into
   *estates* (union-find), each reported with the evidence that produced it.

Every link is explainable: the report states which values were shared and what
each contributed. Correlation only — no scanning, no network.
"""

from __future__ import annotations

import math
import re
from typing import Any, Dict, List, Set, Tuple

from ..core import Result
from ..reporting import _flatten

# --------------------------------------------------------------------------- #
#  Evidence types and their prior selectivity
#  (how strongly a shared value implies "same operator", before frequency)
# --------------------------------------------------------------------------- #
PRIOR: Dict[str, float] = {
    "cert_serial": 0.98,     # same certificate instance
    "cert_spki": 0.97,       # same public key
    "ga_id": 0.96,           # Google Analytics property
    "gtm_id": 0.94,          # Tag Manager container
    "fb_pixel": 0.92,
    "yandex_id": 0.90,
    "hotjar_id": 0.90,
    "adsense_id": 0.90,
    "s3_bucket": 0.88,
    "favicon_hash": 0.82,    # strong, but stock icons exist
    "cert_org": 0.78,        # O= field of the certificate subject
    "jarm": 0.55,            # server TLS stack config
    "ja3s": 0.50,
    "mx_set": 0.45,          # self-hosted mail is telling; Google Workspace isn't
    "ns_set": 0.35,
    "ip": 0.50,
    "asn": 0.18,             # shared hosting provider: weak on its own
    "header_sig": 0.22,
}

# Values that are shared infrastructure by definition. The frequency model
# demotes these on its own once there is enough data; this list keeps small
# scans honest too.
_KNOWN_COMMON = {
    "cloudflare", "amazonaws", "aws", "google", "googlemail", "gmail",
    "azure", "microsoft", "outlook", "akamai", "fastly", "godaddy",
    "namecheap", "digitalocean", "linode", "ovh", "hetzner", "vercel",
    "netlify", "wordpress", "shopify", "squarespace", "wix", "cloudfront",
    "let's encrypt", "lets encrypt", "digicert", "sectigo", "globalsign",
    "r3", "e1", "e5", "e6", "zerossl",
}

# --------------------------------------------------------------------------- #
#  Extraction patterns
# --------------------------------------------------------------------------- #
_PATTERNS: List[Tuple[str, re.Pattern]] = [
    ("ga_id", re.compile(r"\b(UA-\d{4,10}-\d{1,4})\b")),
    ("ga_id", re.compile(r"\b(G-[A-Z0-9]{8,12})\b")),
    ("gtm_id", re.compile(r"\b(GTM-[A-Z0-9]{4,10})\b")),
    ("adsense_id", re.compile(r"\b(ca-pub-\d{10,20})\b")),
    ("yandex_id", re.compile(r"\byandex[^0-9]{0,12}(\d{6,10})\b", re.I)),
    ("hotjar_id", re.compile(r"\bhjid[^0-9]{0,6}(\d{5,9})\b", re.I)),
    ("fb_pixel", re.compile(r"\bfbq\(\s*['\"]init['\"]\s*,\s*['\"](\d{10,20})", re.I)),
    ("s3_bucket", re.compile(r"\b([a-z0-9][a-z0-9.\-]{2,62})\.s3[.\-][a-z0-9.\-]*amazonaws\.com")),
    ("jarm", re.compile(r"\b([0-9a-f]{62})\b")),
]
# field-name driven extraction (value taken as-is from the field)
_FIELD_KEYS: List[Tuple[str, Tuple[str, ...]]] = [
    ("cert_serial", ("serial", "serial_number", "serialnumber")),
    ("cert_spki", ("spki", "spki_sha256", "public_key_hash", "pubkey_hash")),
    ("cert_org", ("organizationname", "subject_org", "cert_org", "organization")),
    ("favicon_hash", ("favicon_hash", "favicon_mmh3", "mmh3", "favhash")),
    ("jarm", ("jarm",)),
    ("ja3s", ("ja3s", "ja3_server")),
    ("asn", ("asn", "as", "as_number")),
]


def _norm(value: Any) -> str:
    return str(value).strip().strip('"\'').lower()


def _is_common(value: str) -> bool:
    """Whether a value is shared infrastructure rather than an identity.

    Matching is boundary-aware on purpose: a naive substring test makes the
    short CA labels ("r3", "e1") match *inside* unrelated hex — the serial
    ``0af3e19b77c2`` contains "e1" — which would silently discard the single
    strongest kind of evidence there is. Short tokens must match the whole
    value; longer ones match on a token boundary.
    """
    v = _norm(value)
    for tok in _KNOWN_COMMON:
        if len(tok) <= 3:
            if v == tok:
                return True
        elif re.search(r"(?<![a-z0-9])" + re.escape(tok) + r"(?![a-z0-9])", v):
            return True
    return False


def extract_fingerprints(results: List[Result]) -> Dict[str, Dict[str, Set[str]]]:
    """host -> evidence type -> {values}, harvested from every module's output."""
    out: Dict[str, Dict[str, Set[str]]] = {}

    def add(host: str, kind: str, value: Any) -> None:
        v = _norm(value)
        if not v or v in ("none", "null", "unknown", "0", "-"):
            return
        if len(v) > 200:
            return
        out.setdefault(host, {}).setdefault(kind, set()).add(v)

    for r in results or []:
        host = _norm(getattr(r, "target", "") or "")
        if not host:
            continue
        flat: Dict[str, str] = {}
        _flatten("", getattr(r, "data", {}) or {}, flat)
        for key, val in flat.items():
            kl = key.lower().split(".")[-1]
            sval = str(val)
            # field-name driven
            for kind, names in _FIELD_KEYS:
                if kl in names and sval:
                    add(host, kind, sval)
            # nameserver / MX sets are compared as sorted sets, not per item
            if kl in ("nameservers", "ns", "name_servers") and sval:
                items = sorted({_norm(x) for x in re.split(r"[;,]", sval) if x.strip()})
                if items:
                    add(host, "ns_set", "|".join(items))
            if kl in ("mx", "mx_records", "mailservers") and sval:
                items = sorted({_norm(x) for x in re.split(r"[;,]", sval) if x.strip()})
                if items:
                    add(host, "mx_set", "|".join(items))
            if kl in ("ip", "ips", "a", "address", "resolved_ip") and sval:
                for ipv in re.findall(r"\b\d{1,3}(?:\.\d{1,3}){3}\b", sval):
                    add(host, "ip", ipv)
            # regex-driven, over the raw value text
            for kind, rx in _PATTERNS:
                for m in rx.findall(sval):
                    add(host, kind, m if isinstance(m, str) else m[0])
    return out


# --------------------------------------------------------------------------- #
#  Selectivity: prior x inverse document frequency over the observed corpus
# --------------------------------------------------------------------------- #
# how many hosts we need before corpus frequency is trustworthy on its own
_FREQ_TRUST_N = 10


def _idf(df: int, n_hosts: int) -> float:
    """1.0 when a value is rare, →0.0 when it is ubiquitous in the corpus.

    Inverse document frequency normalised into [0, 1]
    (``1 - log(df)/log(N)``), with one correction: frequency measured over a
    handful of hosts is a poor estimate of *global* rarity. Sharing a Google
    Analytics property across 2 of 4 scanned hosts does not make that property
    common in the world — it only looks common in a tiny corpus.

    So the frequency signal is trusted in proportion to corpus size, and that
    applies to the "present on every host" case too. With N=2 *everything*
    shared is trivially universal, and zeroing it would make the engine unable
    to compare two hosts at all — the commonest thing anyone asks of it. Below
    the trust threshold the score therefore falls back toward the type's prior,
    and genuinely shared infrastructure is caught by ``_is_common`` instead.
    Once the corpus is large enough, a universal value correctly scores 0.
    """
    if n_hosts <= 1 or df <= 0:
        return 1.0
    trust = min(1.0, (n_hosts - 1) / float(_FREQ_TRUST_N - 1))
    if df >= n_hosts:
        return 0.0 if trust >= 1.0 else (1.0 - trust)
    raw = max(0.0, 1.0 - (math.log(df) / math.log(n_hosts)))
    return raw * trust + 1.0 * (1.0 - trust)


def _is_edge_ip(kind: str, value: str) -> bool:
    """A shared CDN/WAF edge address is not evidence of common ownership —
    thousands of unrelated sites answer from the same Cloudflare node."""
    if kind != "ip":
        return False
    try:
        from ..netclass import classify_ip
        return classify_ip(value)["kind"] in ("cdn", "private")
    except Exception:  # noqa: BLE001
        return False


def evidence_weight(kind: str, value: str, df: int, n_hosts: int) -> float:
    """How much a shared `value` of type `kind` argues for a common operator."""
    prior = PRIOR.get(kind, 0.2)
    weight = prior * _idf(df, n_hosts)
    if _is_common(value) or _is_edge_ip(kind, value):
        weight *= 0.1          # known shared infrastructure: near-worthless
    return round(min(weight, 0.99), 4)


def _fuse(weights: List[float]) -> float:
    """Noisy-OR fusion of independent evidence."""
    product = 1.0
    for w in weights:
        product *= (1.0 - max(0.0, min(w, 0.99)))
    return round(1.0 - product, 4)


# --------------------------------------------------------------------------- #
#  Correlation + clustering
# --------------------------------------------------------------------------- #
class _Union:
    def __init__(self) -> None:
        self.parent: Dict[str, str] = {}

    def find(self, a: str) -> str:
        self.parent.setdefault(a, a)
        while self.parent[a] != a:
            self.parent[a] = self.parent[self.parent[a]]
            a = self.parent[a]
        return a

    def union(self, a: str, b: str) -> None:
        ra, rb = self.find(a), self.find(b)
        if ra != rb:
            self.parent[rb] = ra


def attribute(results: List[Result], target: str = "",
              threshold: float = 0.75) -> Dict[str, Any]:
    """Correlate hosts into operator *estates* with explainable confidence."""
    fps = extract_fingerprints(results)
    hosts = sorted(fps)
    n = len(hosts)
    if n < 2:
        return {"target": target, "hosts_analysed": n, "estates": [], "links": [],
                "note": "attribution needs at least two hosts with extractable "
                        "fingerprints — run a deep/multi-host scan first"}

    # document frequency per (kind, value)
    df: Dict[Tuple[str, str], int] = {}
    for host, kinds in fps.items():
        for kind, values in kinds.items():
            for v in values:
                df[(kind, v)] = df.get((kind, v), 0) + 1

    links: List[Dict[str, Any]] = []
    uf = _Union()
    for i, a in enumerate(hosts):
        for b in hosts[i + 1:]:
            shared: List[Dict[str, Any]] = []
            weights: List[float] = []
            for kind in set(fps[a]) & set(fps[b]):
                for v in fps[a][kind] & fps[b][kind]:
                    w = evidence_weight(kind, v, df[(kind, v)], n)
                    if w <= 0.01:
                        continue
                    shared.append({"type": kind, "value": v[:80], "weight": w,
                                   "seen_on_hosts": df[(kind, v)]})
                    weights.append(w)
            if not weights:
                continue
            conf = _fuse(weights)
            shared.sort(key=lambda s: -s["weight"])
            link = {"a": a, "b": b, "confidence": conf,
                    "evidence": shared[:8], "evidence_count": len(shared)}
            links.append(link)
            if conf >= threshold:
                uf.union(a, b)

    clusters: Dict[str, List[str]] = {}
    for h in hosts:
        clusters.setdefault(uf.find(h), []).append(h)
    estates = []
    for root, members in clusters.items():
        if len(members) < 2:
            continue
        member_set = set(members)
        inner = [ln for ln in links
                 if ln["a"] in member_set and ln["b"] in member_set
                 and ln["confidence"] >= threshold]
        strongest = max((ln["confidence"] for ln in inner), default=0.0)
        # the evidence types that actually built this estate
        drivers: Dict[str, float] = {}
        for ln in inner:
            for e in ln["evidence"]:
                drivers[e["type"]] = max(drivers.get(e["type"], 0.0), e["weight"])
        estates.append({
            "members": sorted(members),
            "size": len(members),
            "confidence": strongest,
            "driving_evidence": dict(sorted(drivers.items(),
                                            key=lambda kv: -kv[1])),
            "links": sorted(inner, key=lambda ln: -ln["confidence"])[:12],
        })
    estates.sort(key=lambda e: (-e["size"], -e["confidence"]))
    links.sort(key=lambda ln: -ln["confidence"])

    demoted = sorted({v for (k, v), c in df.items()
                      if c >= max(2, int(n * 0.8)) or _is_common(v)})[:15]
    return {
        "target": target,
        "hosts_analysed": n,
        "threshold": threshold,
        "estates": estates,
        "estate_count": len(estates),
        "links": links[:40],
        "fingerprint_types": sorted({k for kinds in fps.values() for k in kinds}),
        "demoted_as_shared_infrastructure": demoted,
        "note": "each link's confidence fuses independent shared evidence "
                "(noisy-OR) after weighting every value by its rarity in this "
                "dataset — universal values contribute nothing. Correlation "
                "only; verify before attributing ownership to a real party.",
    }
