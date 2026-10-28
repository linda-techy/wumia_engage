/** One conversation in the list (GET /api/inbox). */
export interface InboxThreadRow {
  identityId: string;
  name: string | null;
  phone: string | null;
  lastMessageAt: string;
  lastInboundAt: string | null;
  lastPreview: string | null;
  unread: number;
  status: 'open' | 'done';
  windowUntil: string | null;
}

export interface InboxMessage {
  id: number;
  direction: 'in' | 'out';
  kind: string;
  body: string | null;
  hasMedia: boolean;
  mediaMime: string | null;
  mediaName: string | null;
  templateKey: string | null;
  /** One of Engage's journey or campaign messages, not typed by staff. */
  automated: boolean;
  sentBy: string | null;
  status: 'sent' | 'delivered' | 'read' | 'failed' | null;
  failedReason: string | null;
  /** Null for a staff message that never went out (status failed, with the reason). */
  wamid: string | null;
  at: string;
}

/** One conversation (GET /api/inbox/{identityId}). */
export interface InboxThread extends InboxThreadRow {
  email: string | null;
  orders: InboxOrder[];
  messages: InboxMessage[];
  windowOpen: boolean;
  followUps: { key: string }[];
  /** The customer agreed to WhatsApp messages (checkout tick box, START, or the in-chat opt-in). */
  waOptIn: boolean;
  /** Templates that may open the chat after the window (V53), and whether Meta approved them. */
  starters: Starter[];
}

export interface Starter {
  key: 'support_followup_v1' | 'support_order_v1';
  approved: boolean;
  orderNumbers?: string[];
}

/** A customer for "New chat" (GET /api/inbox/customers): masked, with what WhatsApp allows. */
export interface ChatCandidate {
  identityId: string;
  name: string | null;
  phone: string | null;
  email: string | null;
  waOptIn: boolean;
  windowOpen: boolean | null;
  hasChat: boolean;
  orders: number;
}

/** "Can reply now", "Opted in", "Not opted in": what staff may do on WhatsApp with this customer. */
export function whatsappStatus(c: Pick<ChatCandidate, 'waOptIn' | 'windowOpen' | 'phone'>): { label: string; tone: 'good' | 'ok' | 'off' } {
  if (!c.phone) return { label: 'No phone number', tone: 'off' };
  if (c.windowOpen) return { label: 'Wrote in the last 24 h: reply freely', tone: 'good' };
  if (c.waOptIn) return { label: 'WhatsApp opted in', tone: 'ok' };
  return { label: 'Not opted in to WhatsApp', tone: 'off' };
}

export interface InboxOrder {
  orderNumber: string;
  totalPaise: number;
  createdAt: string;
  cancelled: boolean;
  shipmentStatus: 'created' | 'in_transit' | 'out_for_delivery' | 'delivered' | 'ndr' | 'rto' | 'cancelled' | null;
  carrier: string | null;
  awb: string | null;
  trackingUrl: string | null;
}

/** A saved answer (GET /api/inbox/quick-replies). */
export interface QuickReply {
  id: number;
  title: string;
  body: string;
  sort: number;
}

const SHIPMENT_LABEL: Record<string, string> = {
  created: 'Handed to courier',
  in_transit: 'In transit',
  out_for_delivery: 'Out for delivery',
  delivered: 'Delivered',
  ndr: 'Delivery failed',
  rto: 'Returning to us',
  cancelled: 'Shipment cancelled',
};

/** "In transit", "Packing" (no shipment yet), "Cancelled". */
export function orderStatus(o: InboxOrder): string {
  if (o.cancelled) return 'Cancelled';
  return o.shipmentStatus ? SHIPMENT_LABEL[o.shipmentStatus] ?? o.shipmentStatus : 'Packing';
}

/** The order's status as a reply, ready to edit and send. */
export function trackingText(o: InboxOrder): string {
  if (o.cancelled) return `Your order ${o.orderNumber} was cancelled.`;
  if (!o.shipmentStatus) {
    return `Your order ${o.orderNumber} is being packed. Orders are dispatched within 1–2 business days, and you'll get the tracking link as soon as it ships.`;
  }
  const courier = o.carrier ? ` with ${o.carrier}${o.awb ? ` (AWB ${o.awb})` : ''}` : '';
  const link = o.trackingUrl ? ` Track it here: ${o.trackingUrl}` : '';
  return `Your order ${o.orderNumber}: ${orderStatus(o).toLowerCase()}${courier}.${link}`;
}

const REFUSALS: Record<string, string> = {
  NO_WHATSAPP_OPT_IN: "the customer hasn't opted in to WhatsApp messages, so only a reply inside their 24-hour window is allowed",
  CHANNEL_HALTED: 'WhatsApp is halted (kill switch)',
  SUPPRESSED: 'this number is suppressed',
  FREQUENCY_CAP: "the customer's daily message limit is reached",
  TEMPLATE_PAUSED: 'Meta has paused this template',
  TEMPLATE_UNKNOWN: 'the template is not set up',
  NO_PHONE: 'no WhatsApp number on file',
  outside_service_window: 'the 24-hour window closed before it went out',
  no_phone: 'no Indian mobile number on file',
  wa_131047: 'the 24-hour window had closed',
  wa_131026: 'this number cannot receive WhatsApp messages',
  wa_131050: 'the customer stopped marketing messages',
};

/** Why a staff message did not go out, in plain words. */
export function refusalText(code: string | null): string {
  if (!code) return 'not sent';
  return REFUSALS[code] ?? code.toLowerCase().replaceAll('_', ' ');
}

/** A saved answer with the customer's first name in place of {name}. */
export function fillName(body: string, name: string | null): string {
  const first = (name ?? '').trim().split(/\s+/)[0] || 'there';
  return body.replaceAll('{name}', first);
}

/** "5h 12m", "48m", "under a minute". */
export function timeLeft(ms: number): string {
  if (ms < 60_000) return 'under a minute';
  const minutes = Math.floor(ms / 60_000);
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  return h > 0 ? `${h}h ${m}m` : `${m}m`;
}

/** The free 24 h reply window: open until the customer's last message + 24 h. */
export function windowState(until: string | null, now: number): { open: boolean; left: string } {
  if (!until) return { open: false, left: '' };
  const ms = new Date(until).getTime() - now;
  return ms > 0 ? { open: true, left: timeLeft(ms) } : { open: false, left: '' };
}
