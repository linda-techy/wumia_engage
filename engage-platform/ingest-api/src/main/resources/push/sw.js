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
    // sig: the push's signature of sid; ingest-api records only signed beacons.
    body: JSON.stringify({ kind, sid: data && data.sid, sig: data && data.sig, sw: SW_VERSION })
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
      data: { url: data.url, sid: data.sid, sig: data.sig }
    });
    return Promise.all([shown, beacon('impression', data)]);
  });
}

self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  const { url = '/', sid, sig } = event.notification.data || {};
  event.waitUntil((async () => {
    beacon('click', { sid, sig });
    // This worker's scope is /apps/push/, so it controls no storefront page, and
    // WindowClient.navigate() only works on pages it controls: navigating an open
    // store tab throws and nothing opens. Focus a tab already on this exact page,
    // otherwise open a new one (always allowed inside notificationclick).
    const target = new URL(url, self.location.origin).href;
    const wins = await clients.matchAll({ type: 'window', includeUncontrolled: true });
    const same = wins.find((w) => w.url === target);
    if (same) return same.focus();
    return clients.openWindow(target);
  })());
});

self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', (e) => e.waitUntil(clients.claim()));
