// Retired. This origin now runs a single service worker (/service-worker.js) —
// having two SWs both claiming scope '/' meant whichever registered last
// deleted the other's cache on activate(), which is what made offline mode
// randomly break across sessions. Any client that still has this script
// installed self-unregisters and hands control to /service-worker.js instead
// of fighting over cache ownership.
//
// It must NOT reload the page while doing so.
//
// This used to end with:
//     const clients = await self.clients.matchAll({ type: 'window' });
//     clients.forEach(client => client.navigate(client.url));
// which was written for a one-off migration: unregister, refresh once, done.
// It is only "one-off" for a page that registers /service-worker.js. The old
// Capacitor/WebView wrapper (android-app/www/index.html) registers THIS script
// on every load, and because activate() has already unregistered it, the next
// register() is a fresh install rather than a no-op. So it ran:
//
//     load -> register('/sw.js') -> install -> activate -> unregister
//          -> navigate() == reload -> load -> register('/sw.js') -> ...
//
// an endless reload. On the device that is a screen flashing over and over with
// nothing clickable, because every tap lands on a page that is about to be
// replaced. Dropping the navigate() breaks the cycle: the worker still retires
// itself, the page simply stays put.
//
// Kept as a live file rather than deleted: a device that still has this
// registered is better served a script that quietly stands down than a 404.
self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', event => {
    event.waitUntil(self.registration.unregister());
});
