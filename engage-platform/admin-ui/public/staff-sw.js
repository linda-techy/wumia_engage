// Staff alerts for the WhatsApp inbox (V50). Engage's worker sends a Web Push
// with no payload, so no customer name or message passes through the browser's
// push service; this worker shows a fixed notification and opens the inbox.
// No fetch handler: the console is never cached or intercepted here.
self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', (event) => event.waitUntil(self.clients.claim()));

self.addEventListener('push', (event) => {
  event.waitUntil(self.registration.showNotification('New WhatsApp message', {
    body: 'A customer is waiting for a reply. Tap to open the WUMIKA inbox.',
    tag: 'wumika-inbox',
    renotify: true,
    icon: '/icons/icon-192.png',
    badge: '/icons/badge-96.png',
    data: { url: '/inbox' },
  }));
});

self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  event.waitUntil((async () => {
    const windows = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
    for (const w of windows) {
      if (new URL(w.url).origin === self.location.origin) {
        await w.focus();
        if ('navigate' in w) await w.navigate('/inbox').catch(() => undefined);
        return;
      }
    }
    await self.clients.openWindow('/inbox');
  })());
});
