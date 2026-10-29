// Shapes of GET /api/dashboard and the small sums and words the screen shows.

import { reasonLabel } from '../analytics/analytics-model';

export type Period = 1 | 7 | 30;

export interface SpendRow {
  channel: string;
  category: string;
  messages: number;
  spentPaise: number;
  budgetPaise: number | null;
}

export interface SendRow {
  channel: string;
  category: string;
  status: string;
  reason: string | null;
  sends: number;
}

export interface JourneyRow {
  intentKey: string;
  entered24h: number;
  succeeded24h: number;
  exhausted24h: number;
  failed24h: number;
  stalled: number;
}

export interface Headline {
  orders: number;
  revenuePaise: number;
  prevOrders: number;
  prevRevenuePaise: number;
  spendPaise: number;
  prevSpendPaise: number;
}

export interface RecoveryRow {
  journey: 'cart_recovery' | 'checkout_abandon';
  started: number;
  recovered: number;
  revenuePaise: number;
}

export interface ChannelRow {
  channel: string;
  sent: number;
  delivered: number;
  read: number;
  clicked: number;
  failed: number;
  blocked: number;
}

export interface WaNumber {
  displayNumber: string;
  verifiedName: string | null;
  quality: 'GREEN' | 'YELLOW' | 'RED' | 'UNKNOWN';
  messagingTier: string | null;
  used24h: number;
}

export interface OptInRow {
  dayIst: string;
  channel: string;
  optedIn: number;
  optedOut: number;
}

export interface Dashboard {
  generatedAt: string;
  days: Period;
  headline: Headline;
  recovery: RecoveryRow[];
  audience: { whatsapp: number; whatsappNew: number; push: number; pushNew: number };
  inbox: { waiting: number; oldestWaitingAt: string | null; unread: number };
  waNumber: WaNumber[];
  channels: ChannelRow[];
  pushFunnel: { shown: number; accepted: number; allowed: number; blocked: number; blockedBySetting: number; closed: number; cannotPush: number };
  waOfferFunnel: { shown: number; tapped: number; joined: number };
  optIns: OptInRow[];
  spendToday: SpendRow[];
  sends24h: SendRow[];
  journeys: JourneyRow[];
  capability: { channel: string; state: string; identities: number }[];
  waTemplates: { status: string; quality: string; templates: number }[];
  waTemplateMismatches: { key: string; language: string; requestedCategory: string; approvedCategory: string }[];
}

/** How full a budget is, 0–100 (capped), or null without a budget. */
export function budgetPct(row: Pick<SpendRow, 'spentPaise' | 'budgetPaise'>): number | null {
  if (!row.budgetPaise) return null;
  return Math.min(100, Math.round((Number(row.spentPaise) / Number(row.budgetPaise)) * 100));
}

/** "42%" of a whole, or "—" when there is nothing to divide by. */
export function rate(part: number, whole: number): string {
  return Number(whole) > 0 ? `${Math.round((Number(part) / Number(whole)) * 100)}%` : '—';
}

/** Revenue for each rupee spent (4.2), or null when nothing was spent. */
export function roi(revenuePaise: number, spendPaise: number): number | null {
  return Number(spendPaise) > 0 ? Number(revenuePaise) / Number(spendPaise) : null;
}

/** Change against the period before, as a whole percent; null when there was nothing before. */
export function change(now: number, before: number): number | null {
  return Number(before) > 0 ? Math.round(((Number(now) - Number(before)) / Number(before)) * 100) : null;
}

/** "Today", "Last 7 days". */
export function periodLabel(days: Period): string {
  return days === 1 ? 'Today' : `Last ${days} days`;
}

/** What the period before is called in comparisons. */
export function previousLabel(days: Period): string {
  return days === 1 ? 'yesterday' : `the ${days} days before`;
}

export const JOURNEY_NAMES: Record<string, string> = {
  cart_recovery: 'Abandoned cart',
  checkout_abandon: 'Abandoned checkout',
  browse_abandon: 'Browsing reminder',
  back_in_stock: 'Back in stock',
  price_drop: 'Price drop',
  welcome: 'Welcome series',
  winback: 'Win-back',
  payment_failed: 'Payment retry',
  post_purchase: 'After delivery',
  order_confirmed: 'Order confirmed',
  order_shipped: 'Order shipped',
  out_for_delivery: 'Out for delivery',
  ndr_raised: 'Delivery failed',
  refund_initiated: 'Refund started',
  wa_stop_confirm: 'STOP confirmation',
};

/** A journey's name in words; newer keys read as words too. */
export function journeyName(key: string): string {
  return JOURNEY_NAMES[key] ?? key.replaceAll('_', ' ').replace(/^./, (c) => c.toUpperCase());
}

/**
 * Journeys worth watching: inbox replies are chats, not journeys (each reply
 * "finishes" at once), and a journey with nothing in 24 h is noise.
 */
export function journeyRows(rows: JourneyRow[]): JourneyRow[] {
  return rows.filter(
    (j) =>
      !j.intentKey.startsWith('inbox_') &&
      Number(j.entered24h) + Number(j.succeeded24h) + Number(j.exhausted24h) + Number(j.failed24h) + Number(j.stalled) > 0,
  );
}

export const CHANNEL_NAMES: Record<string, string> = {
  whatsapp: 'WhatsApp messages',
  whatsapp_chat: 'WhatsApp chat replies',
  push: 'Push',
  sms: 'SMS',
  email: 'Email',
};

/** The Message log's channel filter for a dashboard channel row. */
export function logChannel(channel: string): string {
  return channel === 'whatsapp_chat' ? 'whatsapp' : channel;
}

/** Meta's number quality in words. */
export function qualityText(q: string | null | undefined): string {
  return q === 'GREEN' ? 'High' : q === 'YELLOW' ? 'Medium' : q === 'RED' ? 'Low' : 'Not rated yet';
}

/** People a business may message first in 24 h, by Meta tier; null = no limit or unknown. */
export function tierLimit(tier: string | null | undefined): number | null {
  const limits: Record<string, number> = {
    TIER_50: 50, TIER_250: 250, TIER_1K: 1000, TIER_2K: 2000, TIER_10K: 10000, TIER_100K: 100000,
  };
  return tier ? (limits[tier] ?? null) : null;
}

/** "250 a day", "No limit", "—". */
export function tierText(tier: string | null | undefined): string {
  if (!tier) return '—';
  if (tier === 'TIER_UNLIMITED') return 'No limit';
  const n = tierLimit(tier);
  return n === null ? tier : `${n.toLocaleString('en-IN')} people a day`;
}

/** Sends in the last 24 h that went out (anything past queued), and those that did not. */
export function sendTotals(rows: SendRow[]): { sent: number; blocked: number; deferred: number; failed: number } {
  const t = { sent: 0, blocked: 0, deferred: 0, failed: 0 };
  for (const r of rows) {
    const n = Number(r.sends);
    if (r.status === 'blocked') t.blocked += n;
    else if (r.status === 'deferred') t.deferred += n;
    else if (r.status === 'failed') t.failed += n;
    else if (r.status !== 'queued') t.sent += n;
  }
  return t;
}

export interface ReasonRow {
  reason: string;
  label: string;
  status: string;
  sends: number;
  /** A switch someone turned off on purpose (SMS on hold): shown, but not a fault. */
  expected: boolean;
}

/** Blocked and deferred sends in the last 24 h by reason in words, biggest first. */
export function blockReasons(rows: SendRow[]): ReasonRow[] {
  const by = new Map<string, ReasonRow>();
  for (const r of rows) {
    if (r.status !== 'blocked' && r.status !== 'deferred') continue;
    const reason = r.reason ?? 'UNKNOWN';
    const key = `${r.status}:${reason}`;
    const cur = by.get(key) ?? {
      reason,
      label: reasonLabel(reason),
      status: r.status,
      sends: 0,
      expected: reason === 'CHANNEL_HALTED' || reason === 'QUIET_HOURS',
    };
    cur.sends += Number(r.sends);
    by.set(key, cur);
  }
  return [...by.values()].sort((a, b) => b.sends - a.sends);
}

/** Opt-ins per IST day (WhatsApp and push apart) and opt-outs, oldest first. */
export function optInsByDay(rows: OptInRow[]): { day: string; whatsapp: number; push: number; out: number }[] {
  const by = new Map<string, { day: string; whatsapp: number; push: number; out: number }>();
  for (const r of rows) {
    const cur = by.get(r.dayIst) ?? { day: r.dayIst, whatsapp: 0, push: 0, out: 0 };
    if (r.channel === 'whatsapp') cur.whatsapp += Number(r.optedIn);
    else if (r.channel === 'push') cur.push += Number(r.optedIn);
    cur.out += Number(r.optedOut);
    by.set(r.dayIst, cur);
  }
  return [...by.values()].sort((a, b) => a.day.localeCompare(b.day));
}

/** "35 min", "3 h", "2 days" since an instant. */
export function since(iso: string | null, now = Date.now()): string {
  if (!iso) return '—';
  const min = Math.max(0, Math.floor((now - new Date(iso).getTime()) / 60000));
  if (min < 60) return `${min} min`;
  const h = Math.floor(min / 60);
  return h < 48 ? `${h} h` : `${Math.floor(h / 24)} days`;
}

export interface Attention {
  tone: 'bad' | 'warn' | 'info';
  text: string;
  link?: string;
  query?: Record<string, string>;
}

/** What needs someone today, worst first. */
export function attention(d: Dashboard, now = Date.now()): Attention[] {
  const out: Attention[] = [];
  const n = d.waNumber[0];
  if (n?.quality === 'RED') out.push({ tone: 'bad', text: 'WhatsApp number quality is Low: Meta may limit it. Pause offers and check opt-outs.', link: '/reports' });
  else if (n?.quality === 'YELLOW') out.push({ tone: 'warn', text: 'WhatsApp number quality is Medium: watch opt-outs and blocks.', link: '/reports' });
  const limit = tierLimit(n?.messagingTier);
  if (n && limit && n.used24h >= limit * 0.8) {
    out.push({ tone: n.used24h >= limit ? 'bad' : 'warn', text: `WhatsApp: ${n.used24h} of ${limit} people messaged in 24 h. Past the limit Meta stops new conversations.` });
  }
  for (const m of d.waTemplateMismatches) {
    out.push({ tone: 'bad', text: `Template ${m.key} was approved as ${m.approvedCategory}, not ${m.requestedCategory}: reword and resubmit.`, link: '/templates' });
  }
  for (const s of d.spendToday) {
    const p = budgetPct(s);
    if (p !== null && p >= 80) out.push({ tone: p >= 100 ? 'bad' : 'warn', text: `${CHANNEL_NAMES[s.channel] ?? s.channel} ${s.category} budget ${p}% used today.`, link: '/settings' });
  }
  const stalled = d.journeys.filter((j) => Number(j.stalled) > 0).map((j) => journeyName(j.intentKey));
  if (stalled.length) out.push({ tone: 'bad', text: `Stuck journeys: ${stalled.join(', ')}. The worker may be down.` });
  const failed = sendTotals(d.sends24h).failed;
  if (failed > 0) out.push({ tone: 'warn', text: `${failed} message${failed === 1 ? '' : 's'} failed in 24 h.`, link: '/messages', query: { outcome: 'failed' } });
  if (d.inbox.waiting > 0) {
    out.push({ tone: since(d.inbox.oldestWaitingAt, now).endsWith('min') ? 'info' : 'warn',
      text: `${d.inbox.waiting} WhatsApp chat${d.inbox.waiting === 1 ? '' : 's'} waiting for a reply (oldest ${since(d.inbox.oldestWaitingAt, now)}).`, link: '/inbox' });
  }
  if (d.sends24h.some((r) => r.channel === 'sms' && r.reason === 'CHANNEL_HALTED')) {
    out.push({ tone: 'info', text: 'SMS is switched off (on hold until DLT): SMS steps are skipped.' });
  }
  const rank = { bad: 0, warn: 1, info: 2 };
  return out.sort((a, b) => rank[a.tone] - rank[b.tone]);
}
