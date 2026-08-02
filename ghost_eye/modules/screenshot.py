"""Visual reconnaissance — capture a screenshot of a web asset (Shodan/Censys
style thumbnails). Headless, load-only rendering; no interaction, no payloads.

Requires the optional `playwright` package and a Chromium build. The thumbnail
is returned as a bounded data: URI so reports and the dashboard can show it
inline. FOR AUTHORISED SECURITY TESTING ONLY."""

from __future__ import annotations

import base64
import glob
import io
import os

from ..core import Context, Module, Result, clean_host, ensure_scheme, register


def _find_chromium():
    """Locate a pre-installed Chromium binary (avoids a network download)."""
    base = os.environ.get("PLAYWRIGHT_BROWSERS_PATH", "/opt/pw-browsers")
    for pat in ("chromium-*/chrome-linux/chrome",
                "chromium-*/chrome-linux64/chrome",
                "chromium-*/chrome-win/chrome.exe",
                "chromium-*/chrome-mac/Chromium.app/Contents/MacOS/Chromium"):
        hits = sorted(glob.glob(os.path.join(base, pat)))
        if hits:
            return hits[-1]
    return None

_VIEWPORT = {"width": 1280, "height": 800}
_THUMB_WIDTH = 640
_MAX_DATA_URI = 400_000          # ~400 KB cap on the embedded thumbnail


def _to_thumbnail(png: bytes) -> str:
    """Downscale a PNG to a small JPEG data: URI (falls back to raw PNG)."""
    try:
        from PIL import Image
        im = Image.open(io.BytesIO(png)).convert("RGB")
        if im.width > _THUMB_WIDTH:
            h = int(im.height * _THUMB_WIDTH / im.width)
            im = im.resize((_THUMB_WIDTH, h), Image.LANCZOS)
        buf = io.BytesIO()
        im.save(buf, format="JPEG", quality=68, optimize=True)
        raw = buf.getvalue()
        mime = "image/jpeg"
    except Exception:  # noqa: BLE001 - Pillow missing/broken: embed the raw PNG
        raw, mime = png, "image/png"
    if len(raw) > _MAX_DATA_URI:
        return ""
    return f"data:{mime};base64," + base64.b64encode(raw).decode()


def capture(url: str, timeout: int = 15) -> dict:
    """Render `url` headless and return {status,title,final_url,screenshot,...}.
    Never raises — returns {"error": ...} on any failure."""
    try:
        from playwright.sync_api import sync_playwright
    except Exception as exc:  # noqa: BLE001
        return {"error": f"requires playwright ({exc})"}
    exe = _find_chromium()
    out: dict = {"url": url}
    try:
        with sync_playwright() as p:
            launch = {"headless": True,
                      "args": ["--no-sandbox", "--disable-dev-shm-usage"]}
            if exe:
                launch["executable_path"] = exe
            browser = p.chromium.launch(**launch)
            try:
                page = browser.new_page(viewport=_VIEWPORT,
                                        ignore_https_errors=True)
                resp = page.goto(url, timeout=timeout * 1000,
                                 wait_until="domcontentloaded")
                page.wait_for_timeout(min(1500, timeout * 200))
                png = page.screenshot(full_page=False)
                out.update({
                    "status": resp.status if resp else None,
                    "final_url": page.url,
                    "title": (page.title() or "")[:120],
                    "screenshot": _to_thumbnail(png) or "thumbnail too large",
                })
            finally:
                browser.close()
    except Exception as exc:  # noqa: BLE001
        out["error"] = str(exc)[:160]
    return out


@register
class WebScreenshot(Module):
    id, name, category = "screenshot", "Website screenshot (visual recon)", "Assets"
    target_kind = "url"
    needs = ["playwright + chromium"]

    def run(self, target: str, ctx: Context) -> Result:
        try:
            host = clean_host(target)
        except ValueError as exc:
            return self.fail(target, str(exc))
        res = capture(ensure_scheme(host), timeout=ctx.timeout)
        if res.get("error") and not res.get("screenshot"):
            # retry over http for plain-HTTP hosts
            res2 = capture(ensure_scheme(host, "http"), timeout=ctx.timeout)
            if res2.get("screenshot"):
                res = res2
        if res.get("error") and not res.get("screenshot"):
            return self.fail(host, res["error"])
        return self.ok(host, {
            "final_url": res.get("final_url"),
            "http_status": res.get("status"),
            "title": res.get("title"),
            "screenshot": res.get("screenshot"),
            "note": "load-only viewport capture; render it inline in the "
                    "intelligence report / dashboard",
        })
