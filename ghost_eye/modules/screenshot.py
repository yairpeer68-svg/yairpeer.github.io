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
import shutil
import subprocess
import tempfile

from ..core import Context, Module, Result, clean_host, ensure_scheme, register

# system Chrome/Chromium CLI candidates — this is the Termux/Android path
# (`pkg install chromium`), and the fallback anywhere Playwright is absent.
_CHROME_BINS = ("chromium", "chromium-browser", "chrome", "google-chrome",
                "google-chrome-stable", "chrome-headless-shell", "brave")


def _find_chromium():
    """Locate a Chromium binary: a Playwright build, a system install, or the
    Termux `chromium` package. Returns a path or None."""
    base = os.environ.get("PLAYWRIGHT_BROWSERS_PATH", "/opt/pw-browsers")
    for pat in ("chromium-*/chrome-linux/chrome",
                "chromium-*/chrome-linux64/chrome",
                "chromium-*/chrome-win/chrome.exe",
                "chromium-*/chrome-mac/Chromium.app/Contents/MacOS/Chromium"):
        hits = sorted(glob.glob(os.path.join(base, pat)))
        if hits:
            return hits[-1]
    for name in _CHROME_BINS:
        p = shutil.which(name)
        if p:
            return p
    # common Termux prefix
    for p in ("/data/data/com.termux/files/usr/bin/chromium",
              "/data/data/com.termux/files/usr/bin/chromium-browser"):
        if os.path.exists(p):
            return p
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


def _capture_playwright(url: str, timeout: int) -> dict:
    """Richest backend (page title + HTTP status). Desktop/server."""
    try:
        from playwright.sync_api import sync_playwright
    except Exception as exc:  # noqa: BLE001
        return {"error": f"playwright unavailable ({str(exc)[:60]})"}
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
                    "backend": "playwright",
                })
            finally:
                browser.close()
    except Exception as exc:  # noqa: BLE001
        out["error"] = str(exc)[:160]
    return out


def _capture_cli(url: str, timeout: int) -> dict:
    """Headless Chromium via the command line — the Termux/Android path and the
    universal fallback. No page title/status, but a real screenshot."""
    exe = _find_chromium()
    if not exe:
        return {"error": "no chromium/chrome binary found "
                         "(Termux: `pkg install chromium`)"}
    out: dict = {"url": url}
    tmp = tempfile.mkdtemp(prefix="ge_shot_")
    png_path = os.path.join(tmp, "s.png")
    cmd = [exe, "--headless", "--disable-gpu", "--no-sandbox",
           "--disable-dev-shm-usage", "--hide-scrollbars",
           "--window-size=1280,800",
           f"--virtual-time-budget={min(timeout, 15) * 1000}",
           f"--screenshot={png_path}", url]
    try:
        subprocess.run(cmd, timeout=timeout + 12, capture_output=True)
        if os.path.exists(png_path) and os.path.getsize(png_path) > 0:
            with open(png_path, "rb") as fh:
                png = fh.read()
            out.update({"final_url": url,
                        "screenshot": _to_thumbnail(png) or "thumbnail too large",
                        "backend": "chromium-cli"})
        else:
            out["error"] = "chromium produced no screenshot (blocked/unreachable?)"
    except subprocess.TimeoutExpired:
        out["error"] = "chromium timed out"
    except Exception as exc:  # noqa: BLE001
        out["error"] = str(exc)[:160]
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    return out


def capture(url: str, timeout: int = 15) -> dict:
    """Render `url` headless and return {status,title,final_url,screenshot,...}.
    Tries Playwright first (richer), then the Chromium CLI (Termux-friendly).
    Never raises — returns {"error": ...} if no backend can render."""
    res = _capture_playwright(url, timeout)
    if res.get("screenshot", "").startswith("data:"):
        return res
    cli = _capture_cli(url, timeout)
    if cli.get("screenshot", "").startswith("data:"):
        return cli
    # neither worked — surface the most useful error
    return cli if "no chromium" not in cli.get("error", "") else res


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
