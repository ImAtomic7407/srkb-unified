/**
 * Service Worker - Offline App Shell
 * Caches the registerer page + static assets so the app opens offline.
 */

// v7: unified with the former /sw.js — that script now just redirects
// installs here so there is exactly one service worker per origin. Two
// competing SWs each deleting the other's cache on activate() was the
// root cause of offline mode randomly breaking.
// v8: bumped to force re-fetch of offline-sync.js, which gained a
// self-driving reconnect watchdog so pending records auto-sync the moment
// the server is reachable again — no app reopen required.
// v9: youth/congregation separation — offline-sync.js now carries a per-record
// category and registerer.html gained the category toggle; bump to re-fetch both.
// v10: category toggle now remembers each track's batch/session/date selection
// (registerer.html) — bump so the precached app shell picks up the new page.
// v11: registerer autocomplete now passes the active category to
// /api/search_mobile so recommendations are scoped centre→category — re-fetch
// the page so the new query param ships.
// v12: iOS/macOS install support — real PNG icons replaced the single SVG
// data-URI in manifest.json. THIS BUMP IS NOT OPTIONAL. /manifest.json is served
// by the cache-first static branch below, so an already-installed client would
// keep serving the old icon-less manifest out of attendance-app-v11 forever and
// never discover the new icons. Renaming the cache is the only thing that
// re-fetches it.
const CACHE_NAME = 'attendance-app-v12';
const APP_SHELL = [
  '/login',
  '/register',
  '/static/js/sync.js',
  '/static/js/offline-sync.js',
  '/static/service-worker.js',
  '/static/manifest.json',
  '/static/css/host-tailwind.css',
  '/manifest.json',
  // Precached so a reinstall/re-add while offline still gets a real icon
  // instead of falling back to a screenshot of the page.
  '/static/icons/icon-192.png',
  '/static/icons/icon-512.png',
  '/static/icons/icon-maskable-512.png',
  '/static/icons/apple-touch-icon.png'
];

// Install — cache app shell
self.addEventListener('install', event => {
  event.waitUntil(
    caches.open(CACHE_NAME)
      .then(cache => cache.addAll(APP_SHELL).catch(err => {
        // Don't fail install if some assets are missing
        console.warn('[SW] Some assets failed to cache:', err);
      }))
      .then(() => self.skipWaiting())
  );
});

// Activate — remove old caches
self.addEventListener('activate', event => {
  event.waitUntil(
    caches.keys()
      .then(keys => Promise.all(
        keys.filter(k => k !== CACHE_NAME).map(k => caches.delete(k))
      ))
      .then(() => self.clients.claim())
  );
});

// Fetch strategy:
//   - API calls: network only (never serve API from cache)
//   - Static assets: cache-first
//   - Pages (/registerer, etc.): network-first, fall back to cache
self.addEventListener('fetch', event => {
  const { request } = event;
  const url = new URL(request.url);

  // Only handle GET requests on our own origin
  if (request.method !== 'GET' || url.origin !== self.location.origin) return;

  // API calls — never intercept, let them fail naturally when offline
  if (url.pathname.startsWith('/api/') || url.pathname === '/health') return;

  // Static assets — cache-first
  if (url.pathname.startsWith('/static/') || url.pathname === '/manifest.json') {
    event.respondWith(
      caches.match(request).then(cached => cached || fetch(request).then(res => {
        // Cache newly fetched static assets
        const clone = res.clone();
        caches.open(CACHE_NAME).then(c => c.put(request, clone));
        return res;
      }))
    );
    return;
  }

  // Pages — network-first, fall back to cached version
  event.respondWith(
    fetch(request)
      .then(res => {
        const reqUrl = new URL(request.url);
        const resUrl = new URL(res.url);
        const isRedirected = res.redirected || (reqUrl.pathname !== resUrl.pathname);

        // Cache successful page responses (only if not redirected)
        if (res.ok && !isRedirected) {
          const clone = res.clone();
          caches.open(CACHE_NAME).then(c => c.put(request, clone));
        }
        return res;
      })
      .catch(async () => {
        // Offline — serve from cache, falling back through the app shell so
        // a cold/uncached URL (e.g. the wrapper always opening on '/') still
        // resolves to *something* instead of a hard network error. login.html
        // and registerer.html both know how to resume/redirect from here
        // using the loggedIn/ae_jwt flags in localStorage.
        const cached = (await caches.match(request))
          || (await caches.match('/register'))
          || (await caches.match('/login'));
        if (!cached) return undefined;
        // Chrome refuses to satisfy a *navigation* request with a cached
        // Response whose internal .url differs from the requested URL (e.g.
        // returning the /login body for a request to '/') — it fails the
        // whole navigation with ERR_FAILED instead of just showing it. Strip
        // that mismatched URL by rebuilding the Response from its body.
        if (request.mode === 'navigate' && cached.url !== request.url) {
          const body = await cached.clone().blob();
          return new Response(body, { status: cached.status, statusText: cached.statusText, headers: cached.headers });
        }
        return cached;
      })
  );
});

// Background Sync — flush offline attendance queue when connection restores
self.addEventListener('sync', event => {
  if (event.tag === 'flush-attendance-queue' || event.tag === 'attendease-sync') {
    event.waitUntil(
      self.clients.matchAll().then(clients => {
        clients.forEach(client => {
          client.postMessage({ type: 'ATTENDEASE_BG_SYNC' });
        });
      })
    );
  }
});

console.log('[SW] Loaded v2');
