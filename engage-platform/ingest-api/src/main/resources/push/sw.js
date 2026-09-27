// sw.js — storefront service worker (phase-2 §5). Served by ingest-api at
// /shopify/proxy/sw.js, which the storefront sees same-origin at
// /apps/push/sw.js with scope /apps/push/. Service workers load scripts with
// importScripts, so this uses Firebase's compat builds. Keep the version in
// step with FIREBASE_VERSION in the theme's engage-push.js.
importScripts('https://www.gstatic.com/firebasejs/12.19.0/firebase-app-compat.js');
importScripts('https://www.gstatic.com/firebasejs/12.19.0/firebase-messaging-compat.js');

const SW_VERSION = '__SW_VERSION__';

// The page registers this worker as sw.js?apiKey=…&projectId=… with the
// public Firebase web config from the theme editor, so the config has one
// source of truth and the server holds none of it.
const cfg = Object.fromEntries(new URL(self.location.href).searchParams);

const beacon = (kind, data) =>
  fetch('/apps/push/engagement', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ kind, sid: data && data.sid, sw: SW_VERSION })
  }).catch(() => {});

if (cfg.apiKey && cfg.projectId && cfg.messagingSenderId && cfg.appId) {
  firebase.initializeApp({
    apiKey: cfg.apiKey,
    projectId: cfg.projectId,
    messagingSenderId: cfg.messagingSenderId,
    appId: cfg.appId
  });
  const messaging = firebase.messaging();

  // Every push is data-only (Phase 3). That guarantees this handler runs and
  // we control rendering; a `notification` block would bypass it.
  messaging.onBackgroundMessage(({ data = {} }) => {
    const shown = self.registration.showNotification(data.title || 'Wumika', {
      body: data.body || '',
      icon: data.icon || undefined,
      badge: data.badge || undefined,
      image: data.image || undefined,           // large image: Chrome on Android + desktop
      tag: data.tag || data.kind || 'engage',   // collapses repeats of the same intent
      renotify: false,
      requireInteraction: false,
      data: { url: data.url, sid: data.sid }
    });
    return Promise.all([shown, beacon('impression', data)]);
  });
}

self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  const { url = '/', sid } = event.notification.data || {};
  event.waitUntil((async () => {
    beacon('click', { sid });
    const wins = await clients.matchAll({ type: 'window', includeUncontrolled: true });
    // Reuse an open store tab rather than stacking new ones.
    const tab = wins.find((w) => new URL(w.url).origin === self.location.origin);
    if (tab) { await tab.navigate(url); return tab.focus(); }
    return clients.openWindow(url);
  })());
});

self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', (e) => e.waitUntil(clients.claim()));
