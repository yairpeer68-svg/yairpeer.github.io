/* תוקף — service worker: עבודה לא מקוונת והתראות לפני פקיעת תוקף */
"use strict";

const CACHE = "tokef-v1";
const ASSETS = ["./", "./manifest.webmanifest", "./icons/icon-192.png", "./icons/icon-512.png"];

self.addEventListener("install", e => {
  e.waitUntil(caches.open(CACHE).then(c => c.addAll(ASSETS)).then(() => self.skipWaiting()));
});

self.addEventListener("activate", e => {
  e.waitUntil(
    caches.keys()
      .then(keys => Promise.all(keys.filter(k => k !== CACHE).map(k => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener("fetch", e => {
  if (e.request.method !== "GET" || new URL(e.request.url).origin !== self.location.origin) return;
  e.respondWith(
    caches.open(CACHE).then(async cache => {
      const cached = await cache.match(e.request);
      const fresh = fetch(e.request).then(res => {
        if (res.ok) cache.put(e.request, res.clone());
        return res;
      }).catch(() => cached);
      return cached || fresh;
    })
  );
});

/* ---- IndexedDB mirror ---- */
function idb() {
  return new Promise((res, rej) => {
    const req = indexedDB.open("tokef", 1);
    req.onupgradeneeded = () => req.result.createObjectStore("kv");
    req.onsuccess = () => res(req.result);
    req.onerror = () => rej(req.error);
  });
}
function idbGet(key) {
  return idb().then(db => new Promise((res, rej) => {
    const r = db.transaction("kv").objectStore("kv").get(key);
    r.onsuccess = () => res(r.result);
    r.onerror = () => rej(r.error);
  }));
}
function idbSet(key, val) {
  return idb().then(db => new Promise((res, rej) => {
    const tx = db.transaction("kv", "readwrite");
    tx.objectStore("kv").put(val, key);
    tx.oncomplete = () => res();
    tx.onerror = () => rej(tx.error);
  }));
}

/* בכל בדיקה: פריט שנכנס לחלון ההתראה שלו מקבל התראה — פעם אחת לכל שלב */
async function checkExpiries() {
  const items = (await idbGet("items")) || [];
  const notified = (await idbGet("notified")) || {};
  const now = new Date();
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate());
  let changed = false;

  // השלב הדחוף ביותר שהפריט נמצא בו כרגע; התראה אחת לכל שלב
  function stageFor(days, lead) {
    if (days < 0)  return { key: "expired", text: "פג התוקף של" };
    if (days === 0) return { key: "now", text: "היום פג התוקף של" };
    if (days <= 1) return { key: "day", text: "מחר פג התוקף של" };
    if (days <= 7) return { key: "week", text: "בעוד " + days + " ימים פג התוקף של" };
    if (days <= lead) return { key: "lead", text: "בעוד " + days + " ימים פג התוקף של" };
    return null;
  }

  for (const it of items) {
    if (!it.expiry) continue;
    const days = Math.round((new Date(it.expiry + "T00:00:00") - today) / 86400000);
    const st = stageFor(days, it.lead || 30);
    if (!st) continue;
    const nk = it.id + ":" + st.key + ":" + it.expiry;
    if (notified[nk]) continue;
    notified[nk] = true;
    changed = true;
    await self.registration.showNotification("תוקף 🛡️", {
      body: `${it.emoji || "📄"} ${st.text} ${it.name} — כדאי לחדש עכשיו`,
      icon: "icons/icon-192.png",
      badge: "icons/icon-192.png",
      tag: nk,
      dir: "rtl",
      lang: "he",
    });
  }
  if (changed) await idbSet("notified", notified);
}

self.addEventListener("periodicsync", e => {
  if (e.tag === "check-expiries") e.waitUntil(checkExpiries());
});
self.addEventListener("message", e => {
  if (e.data === "check-expiries") e.waitUntil(checkExpiries());
});
self.addEventListener("notificationclick", e => {
  e.notification.close();
  e.waitUntil(self.clients.matchAll({ type: "window" }).then(list => {
    for (const c of list) if ("focus" in c) return c.focus();
    return self.clients.openWindow("./");
  }));
});
