// Real webhook shapes (the ingest-api test fixtures), made distinct per
// request: a pool of synthetic shoppers, fresh ids and tokens. Every email is
// @example.com; phones are synthetic 91 99xxx numbers. Nothing here is a real
// customer, which is also why the target must have no WhatsApp or SMS
// credentials (README.md).

const FX = '../../../ingest-api/src/test/resources/fixtures/';
const fixture = (name) => JSON.parse(open(FX + name));

const CHECKOUT = fixture('shopify_checkout_update.json');
const CART = fixture('shopify_cart_update.json');
const ORDER = fixture('shopify_order_create.json');
const RAZORPAY = fixture('razorpay_payment_failed.json');
const META_STATUS = fixture('meta_status_delivered.json');

/** Shoppers are reused across events, so the identity graph merges and journeys start and cancel as in real life. */
export const SHOPPERS = Number(__ENV.SHOPPERS || 5000);

export function shopper(n) {
  const id = String(n).padStart(5, '0');
  return {
    n,
    email: `load+${id}@example.com`,
    phone: `+91 99${id.slice(0, 3)} ${id.slice(3)}000`,   // +91 99000 01000: 10 digits, synthetic
    customerId: 7700000000000 + n,
    cartToken: `loadcart${id}`,
    checkoutToken: `loadchk${id}`,
    clientId: `load-client-${id}`,
  };
}

let counter = 0;
/** Unique per VU and iteration, without a shared counter between VUs. */
export function uid() {
  counter += 1;
  return `${__VU}-${__ITER}-${counter}-${Date.now()}`;
}

const clone = (o) => JSON.parse(JSON.stringify(o));
const now = () => new Date().toISOString();

export function checkoutUpdate(s) {
  const b = clone(CHECKOUT);
  b.id = 35000000000000 + s.n;
  b.token = s.checkoutToken;
  b.cart_token = s.cartToken;
  b.email = s.email;
  b.updated_at = now();
  if (b.customer) b.customer = { ...b.customer, id: s.customerId, email: s.email };
  if (b.shipping_address) b.shipping_address = { ...b.shipping_address, phone: s.phone };
  return b;
}

export function cartUpdate(s) {
  const b = clone(CART);
  b.id = s.cartToken;
  b.token = s.cartToken;
  b.updated_at = now();
  return b;
}

export function orderCreate(s) {
  const b = clone(ORDER);
  b.id = 6100000000000 + Math.floor(Math.random() * 1e9);
  b.name = `#L${s.n}-${counter}`;
  b.email = s.email;
  b.contact_email = s.email;
  b.cart_token = s.cartToken;
  b.checkout_token = s.checkoutToken;
  b.created_at = now();
  b.customer = { ...b.customer, id: s.customerId, email: s.email };
  b.shipping_address = { ...b.shipping_address, phone: s.phone };
  b.billing_address = { ...b.billing_address, phone: s.phone };
  return b;
}

export function paymentFailed(s) {
  const b = clone(RAZORPAY);
  const p = b.payload.payment.entity;
  p.id = `pay_LOAD${uid().replace(/-/g, '')}`.slice(0, 30);
  p.email = s.email;
  p.contact = s.phone;
  p.created_at = Math.floor(Date.now() / 1000);
  b.created_at = p.created_at;
  return b;
}

export function metaStatus(s) {
  const b = clone(META_STATUS);
  const st = b.entry[0].changes[0].value.statuses[0];
  st.id = `wamid.LOAD${uid()}`;
  st.timestamp = String(Math.floor(Date.now() / 1000));
  st.recipient_id = s.phone.replace(/\D/g, '');
  return b;
}

export function pixelView(s) {
  return {
    name: 'product_viewed',
    clientId: s.clientId,
    at: now(),
    anonId: null,
    productId: '8801',
    variantId: '44581230001',
    productTitle: 'Linen Kurta',
    productHandle: 'linen-kurta',
    pricePaise: 189900,
  };
}

export function promptEvent(s) {
  return { anonId: `00000000-0000-4000-8000-${String(s.n).padStart(12, '0')}`, surface: 'add_to_cart', step: 'soft_shown',
    platform: 'WEB', browser: 'chrome' };
}
