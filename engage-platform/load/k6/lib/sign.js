// Signatures exactly as each provider sends them, computed per request over
// the body that is actually sent, so ingest-api's real checks run.
import crypto from 'k6/crypto';

/** Shopify webhooks: X-Shopify-Hmac-Sha256 = base64(HMAC-SHA256(api secret, raw body)). */
export function shopifyHmac(secret, body) {
  return crypto.hmac('sha256', secret, body, 'base64');
}

/** Razorpay webhooks: X-Razorpay-Signature = hex(HMAC-SHA256(webhook secret, raw body)). */
export function razorpaySignature(secret, body) {
  return crypto.hmac('sha256', secret, body, 'hex');
}

/** Meta webhooks: X-Hub-Signature-256 = "sha256=" + hex(HMAC-SHA256(app secret, raw body)). */
export function metaSignature(secret, body) {
  return 'sha256=' + crypto.hmac('sha256', secret, body, 'hex');
}

/**
 * Shopify App Proxy: the query (without `signature`) sorted by key, joined as
 * key=value with no separator, HMAC-SHA256 hex with the api secret.
 */
export function appProxyQuery(secret, shop, customerId) {
  const q = {
    logged_in_customer_id: customerId || '',
    path_prefix: '/apps/push',
    shop,
    timestamp: String(Math.floor(Date.now() / 1000)),
  };
  const message = Object.keys(q).sort().map((k) => `${k}=${q[k]}`).join('');
  const signature = crypto.hmac('sha256', secret, message, 'hex');
  const parts = Object.keys(q).sort().map((k) => `${k}=${encodeURIComponent(q[k])}`);
  return parts.join('&') + '&signature=' + signature;
}
