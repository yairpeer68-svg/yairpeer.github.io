/* מאיה — service worker: מטמון בסיסי כדי שהמסך ליד המיטה יעלה גם ברשת חלשה */
"use strict";
const CACHE = "maya-v1";
const ASSETS = [
  "./", "./station.html", "./manifest.webmanifest",
  "./icons/icon-192.png", "./icons/icon-512.png",
];
self.addEventListener("install", e => e.waitUntil(
  caches.open(CACHE).then(c => c.addAll(ASSETS)).then(() => self.skipWaiting())
));
self.addEventListener("activate", e => e.waitUntil(
  caches.keys()
    .then(keys => Promise.all(keys.filter(k => k !== CACHE).map(k => caches.delete(k))))
    .then(() => self.clients.claim())
));
self.addEventListener("fetch", e => {
  if (e.request.method !== "GET" || new URL(e.request.url).origin !== self.location.origin) return;
  // מסמכי HTML: network-first, כדי ששינויי CONFIG יגיעו מיד ולא ייתקעו במטמון
  if (e.request.mode === "navigate" || e.request.destination === "document") {
    e.respondWith(
      fetch(e.request).then(res => {
        const copy = res.clone();
        caches.open(CACHE).then(c => c.put(e.request, copy));
        return res;
      }).catch(() => caches.match(e.request))
    );
    return;
  }
  // שאר הנכסים: stale-while-revalidate
  e.respondWith(caches.open(CACHE).then(async cache => {
    const cached = await cache.match(e.request);
    const fresh = fetch(e.request)
      .then(res => { if (res.ok) cache.put(e.request, res.clone()); return res; })
      .catch(() => cached);
    return cached || fresh;
  }));
});
