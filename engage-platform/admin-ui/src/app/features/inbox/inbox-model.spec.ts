import { fillName, InboxOrder, orderStatus, refusalText, timeLeft, trackingText, whatsappStatus, windowState } from './inbox-model';
import { keyBytes } from './staff-alerts';

const NOW = Date.parse('2026-10-27T12:00:00Z');

describe('inbox reply window', () => {
  it('is open until the customer message + 24 h, with the time left', () => {
    expect(windowState('2026-10-28T05:12:30Z', NOW)).toEqual({ open: true, left: '17h 12m' });
    expect(windowState('2026-10-27T12:48:00Z', NOW)).toEqual({ open: true, left: '48m' });
    expect(windowState('2026-10-27T12:00:30Z', NOW)).toEqual({ open: true, left: 'under a minute' });
  });

  it('is closed when it has passed or the customer never wrote', () => {
    expect(windowState('2026-10-27T11:59:59Z', NOW).open).toBe(false);
    expect(windowState(null, NOW).open).toBe(false);
  });

  it('formats hours and minutes', () => {
    expect(timeLeft(5 * 3_600_000 + 7 * 60_000)).toBe('5h 7m');
    expect(timeLeft(59_000)).toBe('under a minute');
  });
});

describe('inbox orders and saved answers', () => {
  const order = (over: Partial<InboxOrder>): InboxOrder => ({
    orderNumber: '#1042', totalPaise: 149900, createdAt: '2026-10-20T10:00:00Z', cancelled: false,
    shipmentStatus: null, carrier: null, awb: null, trackingUrl: null, ...over,
  });

  it('describes the courier status and writes it as a reply', () => {
    expect(orderStatus(order({}))).toBe('Packing');
    expect(orderStatus(order({ cancelled: true, shipmentStatus: 'in_transit' }))).toBe('Cancelled');
    const shipped = order({ shipmentStatus: 'out_for_delivery', carrier: 'Delhivery', awb: 'AWB77', trackingUrl: 'https://t.example/AWB77' });
    expect(trackingText(shipped)).toBe('Your order #1042: out for delivery with Delhivery (AWB AWB77). Track it here: https://t.example/AWB77');
    expect(trackingText(order({}))).toContain('is being packed');
  });

  it('puts the first name in a saved answer', () => {
    expect(fillName('So sorry, {name}! Send a photo, {name}.', 'Mary Linda')).toBe('So sorry, Mary! Send a photo, Mary.');
    expect(fillName('Hi {name}', null)).toBe('Hi there');
  });

  it('explains a refused message in plain words', () => {
    expect(refusalText('NO_WHATSAPP_OPT_IN')).toContain("hasn't opted in");
    expect(refusalText('SOMETHING_NEW')).toBe('something new');
  });
});

describe('new chat', () => {
  it('says what WhatsApp allows with each customer', () => {
    expect(whatsappStatus({ phone: '+91 98•••43210', waOptIn: false, windowOpen: true }).tone).toBe('good');
    expect(whatsappStatus({ phone: '+91 98•••43210', waOptIn: true, windowOpen: false }).label).toBe('WhatsApp opted in');
    expect(whatsappStatus({ phone: '+91 98•••43210', waOptIn: false, windowOpen: null }).label).toBe('Not opted in to WhatsApp');
    expect(whatsappStatus({ phone: null, waOptIn: true, windowOpen: null }).label).toBe('No phone number');
  });
});

describe('staff alerts', () => {
  it('decodes the base64url public key to the 65-byte point', () => {
    const bytes = keyBytes('BAAB_-8');
    expect(Array.from(bytes)).toEqual([4, 0, 1, 255, 239]);
  });
});
