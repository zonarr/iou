// Service worker: keeps a copy of the app's own files so it opens instantly, even while
// the server is still waking up. API calls are never cached: money data is always live.
const CACHE = 'iou-shell-v2';
const SHELL = [
  '/', '/css/app.css', '/manifest.webmanifest', '/icons/icon.svg',
  '/js/app.js', '/js/lib.js', '/js/api.js', '/js/i18n.js', '/js/money.js', '/js/router.js', '/js/ui.js',
  '/js/feed.js', '/js/views-auth.js', '/js/views-home.js', '/js/views-group.js', '/js/views-forms.js',
  '/js/views-direct.js', '/js/views-activity.js', '/js/vendor/preact-htm.js',
];

self.addEventListener('install', (event) => {
  event.waitUntil(caches.open(CACHE).then((cache) => cache.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((key) => key !== CACHE).map((key) => caches.delete(key))))
      .then(() => self.clients.claim()));
});

self.addEventListener('fetch', (event) => {
  const { request } = event;
  const url = new URL(request.url);
  const isApp = request.method === 'GET' && url.origin === self.location.origin
    && !url.pathname.startsWith('/api/') && !url.pathname.startsWith('/v3/') && !url.pathname.startsWith('/swagger-ui');
  if (!isApp) return; // straight to the network

  // Stale-while-revalidate: answer from the cache at once, refresh the cache in the background.
  event.respondWith(caches.open(CACHE).then(async (cache) => {
    const cached = await cache.match(request, { ignoreSearch: url.pathname === '/' });
    const fresh = fetch(request).then((response) => {
      if (response.ok) cache.put(request, response.clone());
      return response;
    });
    if (cached) {
      fresh.catch(() => { /* offline or server asleep: the cached copy is already on its way */ });
      return cached;
    }
    return fresh;
  }));
});
