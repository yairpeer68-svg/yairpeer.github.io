"""CSP-driven asset discovery.

See ``ghost_eye.cspmap`` for the reasoning. In short: a Content-Security-Policy
is a list, written by the target, of every host their pages may talk to — the
most accurate asset source available, for one request.

Passive: reads one page's headers. Nothing here contacts the hosts it finds.
FOR AUTHORISED USE ONLY.
"""

from __future__ import annotations

from ..core import Context, Module, Result, clean_host, register
from ..cspmap import collect_policies, analyse


@register
class CspAssets(Module):
    id = "cspassets"
    name = "CSP asset discovery (APIs, partners, report sinks)"
    category = "Assets"
    target_kind = "domain"
    expect = ["csp_present"]

    def run(self, target: str, ctx: Context) -> Result:
        try:
            host = clean_host(target)
        except ValueError as exc:
            return self.fail(target, str(exc))
        policies = collect_policies(ctx.session, str(host), ctx.timeout)
        report = analyse(str(host), policies)
        if not report["csp_present"] and not report["report_only_present"]:
            return self.ok(str(host), {
                "csp_present": False,
                "note": "this site publishes no Content-Security-Policy, so "
                        "there is no declared host list to mine — and no CSP "
                        "protection against injected content either.",
                "errors": report["errors"],
            })
        return self.ok(str(host), report)
