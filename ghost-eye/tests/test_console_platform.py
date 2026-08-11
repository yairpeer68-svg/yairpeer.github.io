"""Console behaviours that a browser proved and a test has to keep.

Each of these pins something that was found broken by driving the real page:
a deep link nobody read back, a service worker that served the wrong
application offline, a promise branch that rejected with nobody listening.
Static assertions cannot prove a browser behaves — but they can prove the code
that makes it behave has not been deleted.
"""

from __future__ import annotations

import re
from pathlib import Path

import pytest

STATIC = Path(__file__).resolve().parent.parent / "ghost_eye" / "web_static"


@pytest.fixture(scope="module")
def console():
    return (STATIC / "index.html").read_text(encoding="utf-8")


@pytest.fixture(scope="module")
def sw():
    return (STATIC / "sw.js").read_text(encoding="utf-8")


# --------------------------------------------------------------------------- #
class TestDeepLinks:
    """writeUrl() put the job and workspace in the URL for a year before
    anything read them back, so a shared link dropped the recipient on Scan."""

    def test_boot_reads_the_link_back(self, console):
        assert "const link=readUrl();" in console
        assert "link.view" in console and "link.job" in console

    def test_a_linked_running_scan_keeps_updating(self, console):
        """Freezing on whichever frame was current when the link was opened is
        indistinguishable from a scan that died."""
        boot = console[console.index("async function boot()"):]
        assert 'SNAP.status==="running"' in boot
        assert "openStream()" in boot


class TestServiceWorker:
    def test_the_offline_fallback_is_the_console_not_the_osint_page(self, sw):
        """The console is the home page and holds the cached scan. Falling back
        to /osint silently swapped the user onto a different application."""
        fallback = sw[sw.index(".catch("):]
        assert 'caches.match("/")' in fallback or '? "/osint" : "/"' in fallback
        assert not re.search(r'\|\|\s*caches\.match\("/osint"\)', fallback), \
            "the unconditional /osint fallback is back"

    def test_cache_lookup_ignores_the_query_string(self, sw):
        """Every console URL carries ?token=…, so an exact-URL match would
        essentially never hit and offline would always fall through."""
        assert "ignoreSearch" in sw

    def test_only_a_navigation_falls_back_to_a_page(self, sw):
        """Handing the HTML shell to a request for the manifest makes the
        browser report a parse error on a file that is fine."""
        assert 'e.request.mode !== "navigate"' in sw

    def test_the_cache_name_was_bumped(self, sw):
        """An existing client keeps serving the old shell until the name
        changes, so a fixed fallback would never reach anyone."""
        assert 'CACHE = "ghosteye-v1"' not in sw

    def test_the_api_is_never_cached(self, sw):
        assert 'url.pathname.startsWith("/api/")' in sw
        assert 'e.request.method !== "GET"' in sw


class TestRequestLayer:
    def test_the_inflight_map_does_not_orphan_a_rejection(self, console):
        """run.finally() returns a NEW promise that also rejects, and nothing
        awaits it — so every failed GET raised an unhandled rejection even
        though the caller handled the error correctly."""
        assert "run.catch(()=>{}).finally(()=>INFLIGHT.delete(key));" in console
        assert "run.finally(()=>INFLIGHT.delete(key));" not in console

    def test_unhandled_rejections_are_noticed(self, console):
        assert 'addEventListener("unhandledrejection"' in console


class TestOfflineMode:
    def test_the_findings_are_cached_not_only_the_catalogue(self, console):
        """A cached page that can only list which modules exist is not an
        offline mode."""
        assert 'IDB.put("lastjob"' in console
        assert 'IDB.get("lastjob")' in console

    def test_offline_is_a_banner_not_a_toast(self, console):
        """The backend being gone is a condition, not an event: a toast fades
        and leaves you reading stale findings believing they are live."""
        assert "function offlineBanner(" in console
        assert "Backend unreachable" in console

    def test_the_offline_flag_is_declared_before_it_is_read(self, console):
        """The rejection handler reads OFFLINE and is registered far earlier
        than the tab block the flag used to live in — a let in its temporal
        dead zone throws instead of answering false."""
        assert console.index("let OFFLINE") < \
            console.index('addEventListener("unhandledrejection"')


class TestGraphLayout:
    def test_the_layout_exists_once(self, console):
        """The worker is built from layoutBody.toString(), so the threaded and
        inline paths cannot drift apart."""
        assert "function layoutBody(" in console
        assert "layoutBody.toString()" in console
        assert console.count("for(let step=0; step<180; step++)") == 1, \
            "the simulation loop has been duplicated"

    def test_it_is_cached_on_the_node_and_link_set(self, console):
        """Highlighting a node must not re-run a second of physics to draw the
        same picture in a different colour."""
        assert "function kgKey(" in console
        assert "KGCACHE.key===key" in console

    def test_the_cache_is_not_rebuilt_on_every_call(self, console):
        """Declared inside the renderer it would be re-created per render and
        never hit — the failure mode is silent and total."""
        body = console[console.index("function kgSvg("):]
        assert "const KGCACHE=" not in body, "KGCACHE is scoped inside kgSvg()"

    def test_a_worker_failure_still_produces_a_layout(self, console):
        """A Worker that cannot start must not leave a spinner up for ever."""
        assert "w.onerror=()=>finish(layoutBody(" in console

    def test_the_page_still_loads_nothing_remote(self, console):
        """A recon console that phones out tells someone else which install is
        running. A Blob URL is same-origin; a CDN is not."""
        assert "URL.createObjectURL(new Blob(" in console
        remote = re.findall(r'(?:src|href)\s*=\s*["\']https?://', console)
        assert not remote, f"the console loads something remote: {remote[:3]}"

    def test_the_csp_permits_a_blob_worker_but_not_remote_script(self):
        from ghost_eye.webapp import _CSP
        assert "worker-src 'self' blob:" in _CSP
        assert "blob:" not in _CSP.split("script-src")[1].split(";")[0], \
            "blob: leaked into script-src"


class TestJobTabs:
    def test_one_scan_is_not_a_tab_bar(self, console):
        assert "TABS.length<2" in console

    def test_hiding_the_bar_also_clears_it(self, console):
        """Leaving the old buttons in the DOM leaves their click handlers live
        on a tab that no longer exists."""
        assert 'if(el.hidden){ el.innerHTML=""; return; }' in console

    def test_a_stored_scan_does_not_masquerade_as_a_job(self, console):
        """A stored-scan id is not a job id: setting JOB to one makes every
        per-job endpoint 404 instead of falling back to the snapshot."""
        assert "JOB = tab.live ? tab.id : null;" in console
        assert "ACTIVETAB" in console

    def test_closing_the_active_tab_leaves_you_somewhere(self, console):
        assert "if(ACTIVETAB===x.dataset.close && TABS.length) switchTab(" in console
