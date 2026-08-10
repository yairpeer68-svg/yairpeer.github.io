/* Ghost Eye service worker — offline shell for the dashboards. Network-first
   for navigations and the API, cache-first for the app shell/icons. */
const CACHE = "ghosteye-v1";
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
    }).catch(() => caches.match(e.request).then(m => m || caches.match("/osint")))
  );
});
