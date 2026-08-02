"""Email security-posture detection (v3.8 new features). Detection only."""

from __future__ import annotations

import base64
import re
from typing import List

from ..core import Module, clean_host, register
from ..core import dns_resolver as _resolver




# --------------------------------------------------------------------------- #
#  #19 MX / mail-gateway fingerprint
# --------------------------------------------------------------------------- #
@register
class MxFingerprint(Module):
    id, name, category = "mxfingerprint", "Mail gateway / spam-filter fingerprint", "Email"
    target_kind = "domain"

    _PROVIDERS = [
        ("aspmx.l.google.com", "Google Workspace"),
        ("googlemail.com", "Google Workspace"),
        ("google.com", "Google Workspace"),
        ("mail.protection.outlook.com", "Microsoft 365 / Exchange Online"),
        ("outlook.com", "Microsoft 365"),
        ("pphosted.com", "Proofpoint"),
        ("ppe-hosted.com", "Proofpoint Essentials"),
        ("mimecast.com", "Mimecast"),
        ("barracudanetworks.com", "Barracuda"),
        ("cudamail.com", "Barracuda"),
        ("iphmx.com", "Cisco Secure Email (IronPort)"),
        ("messagelabs.com", "Broadcom/Symantec MessageLabs"),
        ("fortimail", "Fortinet FortiMail"),
        ("trendmicro", "Trend Micro"),
        ("sophos.com", "Sophos"),
        ("forcepoint", "Forcepoint"),
        ("mailcontrol.com", "Forcepoint (mailcontrol)"),
        ("zoho.com", "Zoho Mail"),
        ("zoho.eu", "Zoho Mail"),
        ("protonmail.ch", "Proton Mail"),
        ("proton.me", "Proton Mail"),
        ("messagingengine.com", "Fastmail"),
        ("yandex", "Yandex"),
        ("mailgun.org", "Mailgun"),
        ("sendgrid.net", "SendGrid"),
        ("amazonaws.com", "Amazon SES"),
        ("secureserver.net", "GoDaddy"),
        ("emailsrvr.com", "Rackspace"),
        ("qq.com", "Tencent QQ Mail"),
        ("mxhichina.com", "Alibaba / Aliyun"),
    ]

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        try:
            res = _resolver(ctx)
            mxs = sorted((int(r.preference), str(r.exchange).rstrip(".").lower())
                         for r in res.resolve(host, "MX"))
        except Exception as e:  # noqa: BLE001
            return self.fail(host, f"no MX records ({str(e)[:50]})")
        if not mxs:
            return self.ok(host, {"mx": "none", "note": "domain does not receive mail"})
        providers = []
        for _pref, mx in mxs:
            for needle, label in self._PROVIDERS:
                if needle in mx:
                    if label not in providers:
                        providers.append(label)
                    break
        return self.ok(host, {
            "mx_records": [f"{p} {mx}" for p, mx in mxs],
            "gateway_detected": providers or "self-hosted / unknown",
            "note": "identifying the mail gateway reveals the spam/AV filter in "
                    "front of the domain and any third-party mail dependency"})


# --------------------------------------------------------------------------- #
#  #20 DKIM key-strength audit
# --------------------------------------------------------------------------- #
def _der_int_bit_lengths(data: bytes) -> List[int]:
    """Walk a DER blob and return the bit length of every INTEGER found
    (descends into SEQUENCE/SET and BIT STRING). Used to size an RSA modulus
    without depending on the (optional) cryptography library."""
    out: List[int] = []
    i, n = 0, len(data)
    while i < n:
        tag = data[i]; i += 1
        if i >= n:
            break
        ln = data[i]; i += 1
        if ln & 0x80:
            k = ln & 0x7F
            if i + k > n:
                break
            ln = int.from_bytes(data[i:i + k], "big"); i += k
        if i + ln > n:
            break
        content = data[i:i + ln]
        if tag == 0x02:                       # INTEGER
            c = content
            while len(c) > 1 and c[0] == 0:
                c = c[1:]
            out.append(len(c) * 8)
        elif tag in (0x30, 0x31):             # SEQUENCE / SET -> descend
            out += _der_int_bit_lengths(content)
        elif tag == 0x03 and content:         # BIT STRING -> skip unused-bits byte
            out += _der_int_bit_lengths(content[1:])
        i += ln
    return out


@register
class DkimStrength(Module):
    id, name, category = "dkimstrength", "DKIM key-strength audit", "Email"
    target_kind = "domain"

    _SELECTORS = ["default", "google", "selector1", "selector2", "k1", "k2",
                  "dkim", "mail", "smtp", "s1", "s2", "mandrill", "mailjet",
                  "everlytickey1", "protonmail", "zoho", "amazonses", "mxvault",
                  "fm1", "fm2", "fm3", "pic", "scph0", "sig1"]

    def _pubkey_bits(self, txt: str) -> int:
        m = re.search(r"p=([A-Za-z0-9+/=]+)", txt)
        if not m:
            return 0
        b64 = m.group(1)
        b64 += "=" * (-len(b64) % 4)
        try:
            der = base64.b64decode(b64, validate=False)
        except Exception:  # noqa: BLE001
            return 0
        bits = _der_int_bit_lengths(der)
        # modulus is by far the largest integer in the key
        return max(bits) if bits else 0

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        try:
            res = _resolver(ctx)
        except Exception as e:  # noqa: BLE001
            return self.fail(host, f"dnspython required: {e}")
        found = {}
        weak = []
        for sel in self._SELECTORS:
            name = f"{sel}._domainkey.{host}"
            try:
                answers = res.resolve(name, "TXT")
            except Exception:  # noqa: BLE001
                continue
            txt = "".join(s.decode() if isinstance(s, bytes) else str(s)
                          for a in answers for s in a.strings)
            if "p=" not in txt:
                continue
            if re.search(r"p=\s*(;|$)", txt):
                found[sel] = "REVOKED (empty p=)"
                continue
            bits = self._pubkey_bits(txt)
            ktype = "ed25519" if "k=ed25519" in txt else "rsa"
            if ktype == "ed25519":
                found[sel] = "ed25519 (strong)"
            elif bits == 0:
                found[sel] = "present (could not size key)"
            else:
                verdict = ("WEAK" if bits < 1024 else "weak-ish" if bits < 2048
                           else "ok")
                found[sel] = f"RSA {bits}-bit ({verdict})"
                if bits < 2048:
                    weak.append(f"{sel}: RSA {bits}-bit")
        if not found:
            return self.ok(host, {
                "selectors_found": "none of the common selectors resolved",
                "note": "try the 'dkim' module to brute more selectors, or the "
                        "domain may sign from an unlisted selector"})
        return self.ok(host, {
            "selectors": found,
            "weak_keys": weak or "none",
            "risk": "high" if any("WEAK" in v for v in found.values())
                    else "medium" if weak else "low",
            "note": "RSA keys < 2048 bits are deprecated (RFC 8301); < 1024 are "
                    "forgeable. Rotate to 2048-bit RSA or ed25519."})
