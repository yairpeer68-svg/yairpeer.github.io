/* Ghost Eye service worker — offline shell for the dashboards. Network-first
   for navigations and the API, cache-first for the app shell/icons. */
const CACHE = "ghosteye-v3";
const SHELL = ["/", "/osint", "/static/icon-192.png", "/static/icon-512.png"];
self.addEventListener("install", e => {
  e.waitUntil(caches.open(CACHE).then(c => c.addAll(SHELL)).then(() => self.skipWaiting()));
});
self.addEventListener("activate", e => {
  e.waitUntil(caches.keys().then(ks => Promise.all(
    ks.filter(k => k !== CACHE).map(k => caches.delete(k)))).then(() => self.clients.claim()));
});
self.addEventListener("fetch", e => {
  const url = new URL(e.request.url);
  if (e.request.method !== "GET") return;                 // never cache POSTs
  if (url.pathname.startsWith("/api/")) return;           // API is always live
  // navigations + shell: try network, fall back to cache (offline)
  e.respondWith(
    fetch(e.request).then(r => {
      if (r.ok && url.origin === location.origin) {
        const copy = r.clone(); caches.open(CACHE).then(c => c.put(e.request, copy));
      }
      return r;
    }).catch(() =>
      // ignoreSearch matters more than it looks: every console URL carries
      // ?token=…, and most carry ?job=… and ?w=… too, so an exact-URL match
      // would essentially never hit and every offline load would fall through.
      caches.match(e.request, { ignoreSearch: true }).then(m => {
        if (m) return m;
        // Only a *navigation* may fall back to a page. Handing the HTML shell
        // to a request for the manifest or an icon does not help anyone and
        // makes the browser report a parse error on a file that is fine.
        if (e.request.mode !== "navigate") return Response.error();
        // The console is the home page. This used to fall back to /osint —
        // a leftover from before the routing swap — so losing the backend
        // silently swapped you onto a different application, with none of the
        // cached scan the console had just stored for exactly this moment.
        return caches.match(url.pathname === "/osint" ? "/osint" : "/");
      }))
  );
});
