// P7-T04 load test: real webhook and storefront traffic against ingest-api.
//
//   k6 run -e TARGET=https://staging.example -e ALLOW_TARGET=staging.example \
//          -e SHOPIFY_API_SECRET=… -e SHOPIFY_SHOP_DOMAIN=… -e RAZORPAY_WEBHOOK_SECRET=… \
//          -e WA_APP_SECRET=… -e PIXEL_WRITE_KEY=… [-e PROFILE=smoke] load/k6/webhooks.js
//
// PROFILE=full (default): ramp to 400 events/s over 5 minutes, hold 15, ramp down 2.
// PROFILE=smoke: 20 events/s for a minute, to check the script and the target.
// Read README.md first: the target must never be production.
import http from 'k6/http';
import { check, fail } from 'k6';
import { Counter } from 'k6/metrics';
import { appProxyQuery, metaSignature, razorpaySignature, shopifyHmac } from './lib/sign.js';
import * as ev from './lib/events.js';

const TARGET = (__ENV.TARGET || '').replace(/\/+$/, '');
const env = (k) => __ENV[k] || '';

// ---- the safety gate: refuse anything but an explicitly named non-production host ----
const PRODUCTION = [/(^|\.)wumika\.com$/, /^engage\./, /myshopify\.com$/];
const ALLOWED_DEV = ['engage-dev.wumika.com'];
function host(url) {
  const m = /^https?:\/\/([^/:]+)/.exec(url);
  return m ? m[1].toLowerCase() : '';
}
function gate() {
  const h = host(TARGET);
  if (!h) fail('set TARGET, e.g. -e TARGET=https://staging.example');
  if (h !== (env('ALLOW_TARGET') || '').toLowerCase()) fail(`refusing ${h}: repeat the host in -e ALLOW_TARGET=${h} to confirm`);
  const local = h === 'localhost' || h === '127.0.0.1';
  if (!local && !ALLOWED_DEV.includes(h) && PRODUCTION.some((p) => p.test(h))) {
    fail(`refusing ${h}: it looks like production. Load tests run on staging only`);
  }
  if (h === 'engage-dev.wumika.com' && env('PROFILE') !== 'smoke') {
    fail('engage-dev is a small shared box: only PROFILE=smoke there; the full run is for staging');
  }
  for (const k of ['SHOPIFY_API_SECRET', 'SHOPIFY_SHOP_DOMAIN', 'RAZORPAY_WEBHOOK_SECRET', 'WA_APP_SECRET', 'PIXEL_WRITE_KEY']) {
    if (!env(k)) fail(`set -e ${k}=… (the target's own value)`);
  }
}

const FULL = [
  { target: 400, duration: '5m' },
  { target: 400, duration: '15m' },
  { target: 0, duration: '2m' },
];
const SMOKE = [
  { target: 20, duration: '10s' },
  { target: 20, duration: '50s' },
];

export const options = {
  scenarios: {
    events: {
      executor: 'ramping-arrival-rate',
      startRate: 0,
      timeUnit: '1s',
      preAllocatedVUs: env('PROFILE') === 'smoke' ? 20 : 300,
      maxVUs: env('PROFILE') === 'smoke' ? 60 : 1200,
      stages: env('PROFILE') === 'smoke' ? SMOKE : FULL,
    },
  },
  thresholds: {
    // The pass bar (P7-T04): webhook ack p99 under 500 ms, and not one 5xx.
    'http_req_duration{kind:webhook}': ['p(99)<500'],
    'http_req_duration{kind:storefront}': ['p(99)<800'],
    server_errors: ['count<1'],
    checks: ['rate>0.99'],
    // Too few VUs to keep the rate means the test, not the target, fell short.
    dropped_iterations: ['count<50'],
  },
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
};

const serverErrors = new Counter('server_errors');

export function setup() {
  gate();
  const res = http.get(`${TARGET}/health`, { tags: { kind: 'health' } });
  if (res.status !== 200) fail(`${TARGET}/health answered ${res.status}`);
}

/** Share of traffic per event, as a storefront in a sale hour looks (pixel-heavy). */
const MIX = [
  [0.45, pixel],
  [0.15, cart],
  [0.15, checkout],
  [0.10, metaStatus],
  [0.07, order],
  [0.04, razorpay],
  [0.04, storefrontPrompt],
];

export default function () {
  const s = ev.shopper(Math.floor(Math.random() * ev.SHOPPERS));
  let r = Math.random();
  for (const [share, send] of MIX) {
    if ((r -= share) <= 0) return send(s);
  }
  return pixel(s);
}

function record(res, name, ok) {
  if (res.status >= 500) serverErrors.add(1, { event: name });
  check(res, { [`${name} accepted`]: () => ok(res.status) });
}

function shopify(topic, payload, name) {
  const body = JSON.stringify(payload);
  const res = http.post(`${TARGET}/webhooks/shopify`, body, {
    headers: {
      'Content-Type': 'application/json',
      'X-Shopify-Topic': topic,
      'X-Shopify-Webhook-Id': `load-${ev.uid()}`,
      'X-Shopify-Shop-Domain': env('SHOPIFY_SHOP_DOMAIN'),
      'X-Shopify-Hmac-Sha256': shopifyHmac(env('SHOPIFY_API_SECRET'), body),
    },
    tags: { kind: 'webhook', event: name },
  });
  record(res, name, (st) => st === 200);
}

function cart(s) { shopify('carts/update', ev.cartUpdate(s), 'cart'); }
function checkout(s) { shopify('checkouts/update', ev.checkoutUpdate(s), 'checkout'); }
function order(s) { shopify('orders/create', ev.orderCreate(s), 'order'); }

function razorpay(s) {
  const body = JSON.stringify(ev.paymentFailed(s));
  const res = http.post(`${TARGET}/webhooks/razorpay`, body, {
    headers: {
      'Content-Type': 'application/json',
      'X-Razorpay-Event-Id': `evt_load_${ev.uid()}`,
      'X-Razorpay-Signature': razorpaySignature(env('RAZORPAY_WEBHOOK_SECRET'), body),
    },
    tags: { kind: 'webhook', event: 'razorpay' },
  });
  record(res, 'razorpay', (st) => st === 200);
}

function metaStatus(s) {
  const body = JSON.stringify(ev.metaStatus(s));
  const res = http.post(`${TARGET}/meta/webhook`, body, {
    headers: { 'Content-Type': 'application/json', 'X-Hub-Signature-256': metaSignature(env('WA_APP_SECRET'), body) },
    tags: { kind: 'webhook', event: 'meta' },
  });
  record(res, 'meta', (st) => st === 200);
}

function pixel(s) {
  const res = http.post(`${TARGET}/pixel/events`, JSON.stringify(ev.pixelView(s)), {
    headers: { 'Content-Type': 'application/json', 'X-Engage-Key': env('PIXEL_WRITE_KEY') },
    tags: { kind: 'webhook', event: 'pixel' },
  });
  // 429 is the per-browser limit doing its job, not a failure.
  record(res, 'pixel', (st) => st === 204 || st === 429);
}

function storefrontPrompt(s) {
  const q = appProxyQuery(env('SHOPIFY_API_SECRET'), env('SHOPIFY_SHOP_DOMAIN'), '');
  const res = http.post(`${TARGET}/shopify/proxy/prompt-event?${q}`, JSON.stringify(ev.promptEvent(s)), {
    headers: { 'Content-Type': 'application/json' },
    tags: { kind: 'storefront', event: 'prompt' },
  });
  record(res, 'prompt', (st) => st === 204 || st === 429);
}
