/* מנוימטר — service worker: אפליקציה מותקנת, עבודה לא מקוונת, ותזכורות חידוש */
"use strict";

const CACHE = "minuymeter-v3";
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

// stale-while-revalidate: מגיש מהמטמון מיד ומרענן ברקע
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

/* ---- IndexedDB mirror (הדף מסנכרן לכאן את המנויים כדי שנוכל להתריע גם כשהאפליקציה סגורה) ---- */
function idb() {
  return new Promise((res, rej) => {
    const req = indexedDB.open("minuymeter", 1);
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

/* ---- חישוב החיוב הבא (זהה ללוגיקה שבדף) ---- */
function daysInMonth(y, m) { return new Date(y, m + 1, 0).getDate(); }
function nextCharge(sub, today) {
  const anchor = new Date(sub.anchor + "T00:00:00");
  if (sub.cycle === "monthly") {
    const day = anchor.getDate();
    let y = today.getFullYear(), m = today.getMonth();
    let cand = new Date(y, m, Math.min(day, daysInMonth(y, m)));
    if (cand < today) { m += 1; if (m > 11) { m = 0; y += 1; } cand = new Date(y, m, Math.min(day, daysInMonth(y, m))); }
    return cand;
  }
  let y = today.getFullYear();
  let cand = new Date(y, anchor.getMonth(), Math.min(anchor.getDate(), daysInMonth(y, anchor.getMonth())));
  if (cand < today) { y += 1; cand = new Date(y, anchor.getMonth(), Math.min(anchor.getDate(), daysInMonth(y, anchor.getMonth()))); }
  return cand;
}

async function checkRenewals() {
  const subs = (await idbGet("subs")) || [];
  const notified = (await idbGet("notified")) || {};
  const now = new Date();
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate());
  const fmt = new Intl.NumberFormat("he-IL", { style: "currency", currency: "ILS", minimumFractionDigits: 2 });
  let changed = false;

  for (const s of subs) {
    const d = nextCharge(s, today);
    const days = Math.round((d - today) / 86400000);
    if (days > 1) continue;
    const key = s.id + ":" + d.toISOString().slice(0, 10);
    if (notified[key]) continue;
    notified[key] = true;
    changed = true;
    const when = days === 0 ? "היום" : "מחר";
    await self.registration.showNotification("מנוימטר ⏰", {
      body: `${s.emoji || "💳"} ${s.name} מתחדש ${when} — ${fmt.format(s.price)}`,
      icon: "icons/icon-192.png",
      badge: "icons/icon-192.png",
      tag: key,
      dir: "rtl",
      lang: "he",
    });
  }
  // ניקוי מפתחות ישנים כדי שהרשימה לא תתנפח
  const cutoff = new Date(today.getTime() - 40 * 86400000).toISOString().slice(0, 10);
  for (const k of Object.keys(notified)) {
    if (k.slice(-10) < cutoff) { delete notified[k]; changed = true; }
  }
  if (changed) await idbSet("notified", notified);
}

self.addEventListener("periodicsync", e => {
  if (e.tag === "check-renewals") e.waitUntil(checkRenewals());
});

self.addEventListener("message", e => {
  if (e.data === "check-renewals") e.waitUntil(checkRenewals());
});

self.addEventListener("notificationclick", e => {
  e.notification.close();
  e.waitUntil(self.clients.matchAll({ type: "window" }).then(list => {
    for (const c of list) if ("focus" in c) return c.focus();
    return self.clients.openWindow("./");
  }));
});
