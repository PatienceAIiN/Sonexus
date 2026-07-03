// SoNex Web: offline-capable shell (cache-first for the app, network for API).
const CACHE = "sonex-web-v2";
const SHELL = ["/app/", "/app/index.html", "/app/manifest.webmanifest", "/app/icon.svg"];
self.addEventListener("install", e => {
  e.waitUntil(caches.open(CACHE).then(c => c.addAll(SHELL)).then(() => self.skipWaiting()));
});
self.addEventListener("activate", e => {
  e.waitUntil(caches.keys().then(ks => Promise.all(
    ks.filter(k => k !== CACHE).map(k => caches.delete(k)))).then(() => self.clients.claim()));
});
self.addEventListener("fetch", e => {
  const url = new URL(e.request.url);
  if (url.pathname.startsWith("/v1/")) return; // API always live
  e.respondWith(caches.match(e.request).then(hit => hit || fetch(e.request)));
});
