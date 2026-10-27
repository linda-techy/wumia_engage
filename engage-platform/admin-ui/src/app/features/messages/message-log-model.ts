/** One send (GET /api/message-log). */
export interface LogRow {
  id: number;
  createdAt: string;
  channel: 'push' | 'whatsapp' | 'sms' | 'email';
  category: string;
  templateKey: string;
  intentKey: string | null;
  status: 'blocked' | 'deferred' | 'queued' | 'sent' | 'delivered' | 'read' | 'clicked' | 'failed';
  failedReason: string | null;
  blockReason: string | null;
  sentAt: string | null;
  deliveredAt: string | null;
  readAt: string | null;
  clickedAt: string | null;
  costPaise: number | null;
  identityId: string;
  name: string | null;
  phone: string | null;
  email: string | null;
  /** WhatsApp: the text that went out. */
  text: string | null;
  templateTitle: string | null;
  templateBody: string | null;
  campaign: string | null;
}

export interface LogPage {
  rows: LogRow[];
  counts: Record<string, number>;
  more?: boolean;
  from?: string;
  to?: string;
  customerNotFound?: boolean;
}

const JOURNEYS: Record<string, string> = {
  cart_recovery: 'Cart recovery',
  checkout_abandon: 'Checkout recovery',
  browse_abandon: 'Browsing reminder',
  back_in_stock: 'Back in stock',
  price_drop: 'Price drop',
  welcome: 'Welcome series',
  winback: 'Win-back',
  payment_failed: 'Payment retry',
  post_purchase: 'After delivery',
  order_tracking: 'Order updates',
  inbox_greeting: 'Inbox greeting',
  inbox_away: 'Inbox away message',
  inbox_auto_reply: 'Inbox menu answer',
  inbox_staff_reply: 'Staff reply',
  inbox_followup: 'Staff follow-up',
  wa_stop_confirm: 'STOP confirmation',
};

/** "Cart recovery", "Campaign: Diwali Sale", or the key itself for anything newer. */
export function journeyLabel(r: Pick<LogRow, 'intentKey' | 'campaign'>): string {
  if (!r.intentKey) return 'Direct';
  if (r.intentKey.startsWith('campaign:')) return 'Campaign: ' + (r.campaign ?? r.intentKey.slice(9, 17));
  const base = Object.keys(JOURNEYS).find((k) => r.intentKey === k || r.intentKey!.startsWith(k + '.') || r.intentKey!.startsWith(k + '_v'));
  return base ? JOURNEYS[base] : r.intentKey.replaceAll('_', ' ');
}

/** The words: what WhatsApp actually sent, otherwise the template wording ({{name}} stays a placeholder). */
export function messageText(r: Pick<LogRow, 'text' | 'templateTitle' | 'templateBody'>): string {
  if (r.text) return r.text;
  return [r.templateTitle, r.templateBody].filter((s) => !!s).join(' — ');
}

const REASONS: Record<string, string> = {
  QUIET_HOURS: 'quiet hours (sent later)',
  NO_WHATSAPP_OPT_IN: 'no WhatsApp opt-in',
  NO_MARKETING_CONSENT: 'no marketing consent',
  FREQUENCY_CAP: 'daily message limit reached',
  TEMPLATE_COOLDOWN: 'sent recently already',
  CHANNEL_HALTED: 'channel halted',
  MARKETING_HALTED: 'marketing halted',
  JOURNEY_DISABLED: 'journey switched off',
  HOLDOUT_CONTROL: 'control group (not messaged on purpose)',
  HOLDOUT_GLOBAL: 'control group (not messaged on purpose)',
  HIGHER_PRIORITY_ACTIVE: 'a more important message was in progress',
  SUPPRESSED: 'suppressed',
  NO_PUSH_DEVICE: 'no push device',
  NO_PHONE: 'no phone number',
  DAILY_BUDGET_EXHAUSTED: 'daily budget used up (sent later)',
  dlt_pending: 'SMS template not registered on DLT',
  outside_service_window: 'outside the 24-hour WhatsApp window',
  no_phone: 'no Indian mobile number',
  wa_131026: 'number cannot receive WhatsApp',
  wa_131030: 'not a test recipient (Meta test number)',
  wa_131047: '24-hour window had closed',
  wa_131050: 'customer stopped marketing on WhatsApp',
};

export function reasonText(code: string | null): string {
  if (!code) return '';
  return REASONS[code] ?? code.toLowerCase().replaceAll('_', ' ');
}

/** "Read", "Delivered", "Blocked: quiet hours (sent later)". */
export function outcomeText(r: Pick<LogRow, 'status' | 'failedReason' | 'blockReason'>): string {
  switch (r.status) {
    case 'clicked': return 'Tapped';
    case 'read': return 'Read';
    case 'delivered': return 'Delivered';
    case 'sent': return 'Sent';
    case 'queued': return 'Sending';
    case 'deferred': return 'Held: ' + reasonText(r.blockReason);
    case 'blocked': return 'Not sent: ' + reasonText(r.blockReason);
    case 'failed': return 'Failed: ' + reasonText(r.failedReason);
  }
}

export function outcomeTone(status: LogRow['status']): 'good' | 'wait' | 'bad' | 'plain' {
  if (status === 'clicked' || status === 'read' || status === 'delivered') return 'good';
  if (status === 'deferred' || status === 'queued') return 'wait';
  if (status === 'failed' || status === 'blocked') return 'bad';
  return 'plain';
}
