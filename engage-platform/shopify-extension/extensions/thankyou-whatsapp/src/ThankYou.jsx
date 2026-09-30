// Thank you page WhatsApp opt-in (phase-2 §6.2, P2-T05).
// Target: purchase.thank-you.block.render. Preact + Polaris web components.
//
// Sends only the order id and the copy version, with Shopify's session token
// as the bearer. The phone is taken from the order on the server, never from
// here. CONSENT_TEXT is registered as ty_wa_v1 (V12): change a word and you
// need a new version registered first.
import '@shopify/ui-extensions/preact';
import { render } from 'preact';
import { useState } from 'preact/hooks';

const COPY_VERSION = 'ty_wa_v1';
const CONSENT_TEXT = 'Get dispatch, delivery and exchange updates from WUMIKA on WhatsApp.';

export default async () => {
  render(<WhatsAppOptIn />, document.body);
};

function WhatsAppOptIn() {
  const [state, setState] = useState('idle');     // idle | saving | done | error
  const base = (shopify.settings.value.ingest_url || '').replace(/\/+$/, '');
  if (!base) return null;                          // not configured in the checkout editor: show nothing

  async function optIn() {
    setState('saving');
    try {
      const token = await shopify.sessionToken.get();
      const orderId = shopify.orderConfirmation.value.order.id;
      const res = await fetch(`${base}/shopify/thankyou/whatsapp-optin`, {
        method: 'POST',
        headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({ orderId, copyVersion: COPY_VERSION })
      });
      setState(res.ok ? 'done' : 'error');
    } catch {
      setState('error');
    }
  }

  if (state === 'done') {
    return <s-banner tone="success">Done. Delivery updates will come on WhatsApp.</s-banner>;
  }
  return (
    <s-section heading="Track this order on WhatsApp">
      <s-stack gap="base">
        <s-paragraph>{CONSENT_TEXT}</s-paragraph>
        {state === 'error' && <s-banner tone="critical">That did not go through. Please try again.</s-banner>}
        <s-button onClick={optIn} loading={state === 'saving'}>Yes, send updates on WhatsApp</s-button>
      </s-stack>
    </s-section>
  );
}
