// Engage web pixel (phase-2 §7). Runs in Shopify's strict sandbox and posts
// timing hints to ingest-api /pixel/events. It sends NO personal data: a
// checkout token, a product, a timestamp, and the push embed's first-party
// anon id when this browser has one (ingest never creates identities from it).
import { register } from '@shopify/web-pixels-extension';

const CHECKOUT_STEPS = [
  'checkout_started', 'checkout_contact_info_submitted', 'checkout_shipping_info_submitted',
  'payment_info_submitted', 'checkout_completed'
];

// "gid://shopify/ProductVariant/123" or "123" -> "123"
const id = (gid) => (gid == null ? null : String(gid).split('/').pop());
const paise = (money) => {
  const n = Number(money?.amount);
  return Number.isFinite(n) ? Math.round(n * 100) : null;
};
// "/en/products/linen-kurta?variant=1" -> "linen-kurta"
const handle = (url) => {
  const m = /\/products\/([^/?#]+)/.exec(url || '');
  return m ? decodeURIComponent(m[1]) : null;
};

const product = (name, e, variant) => ({
  name, clientId: e.clientId, at: e.timestamp,
  productId: id(variant?.product?.id), variantId: id(variant?.id),
  productTitle: variant?.product?.title ?? null, productHandle: handle(variant?.product?.url),
  pricePaise: paise(variant?.price)
});

register(({ analytics, browser, settings }) => {
  if (!settings.ingestUrl || !settings.writeKey) return;

  let anon;
  const anonId = async () => {
    if (anon === undefined) {
      try { anon = (await browser.localStorage.getItem('engage:anon')) || null; } catch { anon = null; }
    }
    return anon;
  };

  const send = async (body) => {
    try {
      await fetch(settings.ingestUrl, {
        method: 'POST',
        keepalive: true,
        headers: { 'Content-Type': 'application/json', 'X-Engage-Key': settings.writeKey },
        body: JSON.stringify({ ...body, anonId: await anonId() })
      });
    } catch { /* a timing hint: never break the page over it */ }
  };

  for (const name of CHECKOUT_STEPS) {
    analytics.subscribe(name, (e) => send({
      name, clientId: e.clientId, at: e.timestamp,
      checkoutToken: e.data?.checkout?.token ?? null,
      totalPaise: paise(e.data?.checkout?.totalPrice)
    }));
  }
  analytics.subscribe('product_viewed', (e) => send(product('product_viewed', e, e.data?.productVariant)));
  analytics.subscribe('product_added_to_cart', (e) =>
    send(product('product_added_to_cart', e, e.data?.cartLine?.merchandise)));
});
