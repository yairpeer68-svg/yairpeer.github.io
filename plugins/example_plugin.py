"""Example Ghost Eye plugin.

Drop any .py file with a @register-decorated Module subclass into a directory,
then load it:   ghost_eye.py -t example.com -m httpdate --plugins ./plugins

This one reads the server's Date header and reports clock skew vs local time.
"""

from __future__ import annotations

from datetime import datetime, timezone
from email.utils import parsedate_to_datetime

from ghost_eye.core import Module, clean_host, ensure_scheme, register


@register
class HttpDateSkew(Module):
    id = "httpdate"
    name = "HTTP Date header / clock skew"
    category = "Web"
    target_kind = "url"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as exc:
            return self.fail(target, str(exc))
        try:
            r = ctx.session.get(ensure_scheme(host), timeout=ctx.timeout)
        except Exception as exc:  # noqa: BLE001
            return self.fail(host, f"request failed: {exc}")
        date_hdr = r.headers.get("Date")
        if not date_hdr:
            return self.ok(host, {"note": "no Date header"})
        try:
            server_time = parsedate_to_datetime(date_hdr)
            skew = (datetime.now(timezone.utc) - server_time).total_seconds()
        except Exception:
            return self.ok(host, {"date_header": date_hdr})
        return self.ok(host, {"server_date": date_hdr,
                              "clock_skew_seconds": round(skew, 1),
                              "note": "large skew can break TOTP / cert validation"
                              if abs(skew) > 60 else "clock in sync"})
