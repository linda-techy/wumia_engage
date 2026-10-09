// Shapes of GET /api/dashboard (V16 views) and the small sums the screen shows.

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

export interface ConsentRow {
  dayIst: string;
  channel: string;
  purpose: string;
  state: 'granted' | 'withdrawn';
  source: string;
  records: number;
}

export interface Dashboard {
  generatedAt: string;
  spendToday: SpendRow[];
  sends24h: SendRow[];
  journeys: JourneyRow[];
  consentDaily: ConsentRow[];
  capability: { channel: string; state: string; identities: number }[];
  pushDevices: { browser: string; state: string; devices: number }[];
  pushFunnel7d: { surface: string; softShown: number; softAccepted: number; nativeGranted: number; tokenMinted: number; iosRedirected: number }[];
  waTemplates: { status: string; quality: string; templates: number }[];
  waTemplateMismatches: { key: string; language: string; requestedCategory: string; approvedCategory: string }[];
}

/** How full a budget is, 0–100 (capped), or null without a budget. */
export function budgetPct(row: Pick<SpendRow, 'spentPaise' | 'budgetPaise'>): number | null {
  if (!row.budgetPaise) return null;
  return Math.min(100, Math.round((Number(row.spentPaise) / Number(row.budgetPaise)) * 100));
}

/** Blocked and deferred sends in the last 24 h by reason, biggest first. */
export function blockReasons(rows: SendRow[]): { reason: string; status: string; sends: number }[] {
  const by = new Map<string, { reason: string; status: string; sends: number }>();
  for (const r of rows) {
    if (r.status !== 'blocked' && r.status !== 'deferred') continue;
    const reason = r.reason ?? 'UNKNOWN';
    const key = `${r.status}:${reason}`;
    const cur = by.get(key) ?? { reason, status: r.status, sends: 0 };
    cur.sends += Number(r.sends);
    by.set(key, cur);
  }
  return [...by.values()].sort((a, b) => b.sends - a.sends);
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

/** Opt-outs per IST day, oldest first, for the last `days` days that have any consent activity. */
export function optOutsByDay(rows: ConsentRow[], days = 14): { day: string; optOuts: number; optIns: number }[] {
  const by = new Map<string, { day: string; optOuts: number; optIns: number }>();
  for (const r of rows) {
    const cur = by.get(r.dayIst) ?? { day: r.dayIst, optOuts: 0, optIns: 0 };
    if (r.state === 'withdrawn') cur.optOuts += Number(r.records);
    else cur.optIns += Number(r.records);
    by.set(r.dayIst, cur);
  }
  return [...by.values()].sort((a, b) => a.day.localeCompare(b.day)).slice(-days);
}
