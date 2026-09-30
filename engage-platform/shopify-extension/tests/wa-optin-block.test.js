// wa-optin.liquid (P2-T07): the cart page WhatsApp checkbox. Rendered here
// without Liquid (tags stripped), with the embed's EngageConfig present.
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const src = readFileSync(resolve(process.cwd(), 'extensions/push-embed/blocks/wa-optin.liquid'), 'utf8');

function render({ ticked = false, config = { whatsapp: 'Send me order updates and offers from WUMIKA on WhatsApp', whatsappVersion: 'wa_v1' } } = {}) {
  document.body.innerHTML = '';
  // Inline scripts run in jsdom's own global (vitest's `jsdom.window`), not the test's.
  const win = jsdom.window;
  win.EngageConfig = config ? { copy: config } : undefined;
  win.EngagePush = { setWhatsAppOptIn: vi.fn() };

  const body = src.split('{% schema %}')[0]
    .replace(/{%-?\s*comment\s*-?%}[\s\S]*?{%-?\s*endcomment\s*-?%}/g, '')
    .replace(/{%\s*if ticked\s*%}checked{%\s*endif\s*%}/g, ticked ? 'checked' : '')
    .replace(/{%-?[\s\S]*?-?%}/g, '')
    .replace(/{{[\s\S]*?}}/g, '');
  const tpl = document.createElement('template');
  tpl.innerHTML = body;
  // Scripts from innerHTML do not run; re-create each so it executes in place.
  for (const node of [...tpl.content.childNodes]) {
    if (node.nodeName === 'SCRIPT') {
      const s = document.createElement('script');
      s.textContent = node.textContent;
      document.body.append(s);
    } else {
      document.body.append(node);
    }
  }
  return document.querySelector('[data-engage-wa-optin]');
}

describe('cart WhatsApp checkbox', () => {
  beforeEach(() => { document.body.innerHTML = ''; });

  it('starts unticked and shows the registered words', () => {
    const box = render();
    expect(box.checked).toBe(false);
    expect(document.querySelector('.engage-wa-optin').hidden).toBe(false);
    expect(document.querySelector('.engage-wa-optin__text').textContent)
      .toBe('Send me order updates and offers from WUMIKA on WhatsApp');
  });

  it('ticking and unticking write the cart attributes through engage-push.js', () => {
    const box = render();
    box.click();
    expect(jsdom.window.EngagePush.setWhatsAppOptIn).toHaveBeenLastCalledWith(true);
    box.click();
    expect(jsdom.window.EngagePush.setWhatsAppOptIn).toHaveBeenLastCalledWith(false);
  });

  it('is shown ticked only when this cart was ticked earlier', () => {
    expect(render({ ticked: true }).checked).toBe(true);
  });

  it('shows nothing when the embed is off or has no WhatsApp copy', () => {
    render({ config: null });
    expect(document.querySelector('.engage-wa-optin').hidden).toBe(true);
    render({ config: { whatsapp: 'x' } });
    expect(document.querySelector('.engage-wa-optin').hidden).toBe(true);
  });
});
