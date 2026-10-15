import { AnalyticsDay, BlockDay, Funnel, SpendDay } from '../../core/api/models';

/** "12.3%" of part over whole, or "—" when there is no whole. */
export function rate(part: number, whole: number): string {
  if (!whole) return '—';
  return `${((100 * part) / whole).toFixed(1)}%`;
}

/** Revenue per rupee spent: "₹4.2" back for each ₹1, or "—" when nothing was spent. */
export function returnPerRupee(f: Pick<Funnel, 'revenuePaise' | 'costPaise'>): string {
  return f.costPaise > 0 ? `₹${(f.revenuePaise / f.costPaise).toFixed(1)}` : '—';
}

/** yyyy-MM-dd in IST, `days` days ending today. */
export function lastDays(days: number, now = new Date()): { from: string; to: string } {
  const ist = (d: Date) => new Date(d.getTime() + 330 * 60_000).toISOString().slice(0, 10);
  return { from: ist(new Date(now.getTime() - (days - 1) * 86_400_000)), to: ist(now) };
}

export type Metric = 'sent' | 'clicked' | 'orders' | 'revenuePaise';

export interface Bar {
  label: string;
  value: number;
}

/** One bar per day of the range, all channels added up; days without sends are zero, not missing. */
export function dailyBars(days: AnalyticsDay[], metric: Metric, from: string, to: string): Bar[] {
  const byDay = new Map<string, number>();
  for (const d of days) byDay.set(d.day, (byDay.get(d.day) ?? 0) + d[metric]);
  const out: Bar[] = [];
  for (let t = Date.parse(from + 'T00:00:00Z'); t <= Date.parse(to + 'T00:00:00Z'); t += 86_400_000) {
    const day = new Date(t).toISOString().slice(0, 10);
    out.push({ label: day, value: byDay.get(day) ?? 0 });
  }
  return out;
}

/** Spend added up by channel and category over the range. */
export function spendTotals(days: SpendDay[]): { channel: string; category: string; messages: number; paise: number }[] {
  const m = new Map<string, { channel: string; category: string; messages: number; paise: number }>();
  for (const d of days) {
    const k = `${d.channel}|${d.category}`;
    const row = m.get(k) ?? { channel: d.channel, category: d.category, messages: 0, paise: 0 };
    row.messages += d.messages;
    row.paise += d.paise;
    m.set(k, row);
  }
  return [...m.values()].sort((a, b) => b.paise - a.paise);
}

/** Why messages were held back, added up over the range, largest first. */
export function blockTotals(days: BlockDay[]): { reason: string; status: string; sends: number }[] {
  const m = new Map<string, { reason: string; status: string; sends: number }>();
  for (const d of days) {
    const k = `${d.status}|${d.reason}`;
    const row = m.get(k) ?? { reason: d.reason, status: d.status, sends: 0 };
    row.sends += d.sends;
    m.set(k, row);
  }
  return [...m.values()].sort((a, b) => b.sends - a.sends);
}

/** Policy reasons in plain words. */
export function reasonLabel(reason: string): string {
  const words: Record<string, string> = {
    QUIET_HOURS: 'Quiet hours (night)',
    FREQUENCY_CAP: 'Already messaged enough',
    NO_MARKETING_CONSENT: 'No opt-in for offers',
    NO_WHATSAPP_OPT_IN: 'No WhatsApp opt-in',
    SUPPRESSED: 'Opted out or bounced',
    UNREACHABLE: 'No phone or device',
    HOLDOUT_CONTROL: 'Held out (journey measurement)',
    HOLDOUT_GLOBAL: 'Held out (overall measurement)',
    HIGHER_PRIORITY_ACTIVE: 'A more important message was in progress',
    DAILY_BUDGET_EXHAUSTED: "Today's budget used up",
    MARKETING_HALTED: 'Marketing halted',
    CHANNEL_HALTED: 'Channel halted',
    JOURNEY_DISABLED: 'Journey switched off',
    TEMPLATE_COOLDOWN: 'Same message sent recently',
    TEMPLATE_PAUSED: 'Template paused',
    TEMPLATE_UNKNOWN: 'Template not found',
    CHANNEL_MISMATCH: 'Template is for another channel',
    WA_BACKOFF: 'WhatsApp asked us to slow down',
  };
  return words[reason] ?? reason.replace(/_/g, ' ').toLowerCase();
}
