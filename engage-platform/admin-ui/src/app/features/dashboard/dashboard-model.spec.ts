import { blockReasons, budgetPct, optOutsByDay, sendTotals } from './dashboard-model';

describe('dashboard model', () => {
  it('fills the budget bar and caps it at 100', () => {
    expect(budgetPct({ spentPaise: 51600, budgetPaise: 200000 })).toBe(26);
    expect(budgetPct({ spentPaise: 300000, budgetPaise: 200000 })).toBe(100);
    expect(budgetPct({ spentPaise: 100, budgetPaise: null })).toBeNull();
  });

  it('sums block reasons across channels, biggest first', () => {
    const rows = [
      { channel: 'push', category: 'marketing', status: 'blocked', reason: 'NO_CONSENT', sends: 3 },
      { channel: 'whatsapp', category: 'marketing', status: 'blocked', reason: 'NO_CONSENT', sends: 4 },
      { channel: 'push', category: 'marketing', status: 'deferred', reason: 'QUIET_HOURS', sends: 2 },
      { channel: 'push', category: 'utility', status: 'sent', reason: null, sends: 9 },
    ];
    expect(blockReasons(rows)).toEqual([
      { reason: 'NO_CONSENT', status: 'blocked', sends: 7 },
      { reason: 'QUIET_HOURS', status: 'deferred', sends: 2 },
    ]);
    expect(sendTotals(rows)).toEqual({ sent: 9, blocked: 7, deferred: 2, failed: 0 });
  });

  it('counts opt-outs and opt-ins per day, oldest first', () => {
    const rows = [
      { dayIst: '2026-10-09', channel: 'whatsapp', purpose: 'marketing', state: 'withdrawn' as const, source: 'wa_stop_reply', records: 2 },
      { dayIst: '2026-10-08', channel: 'whatsapp', purpose: 'marketing', state: 'granted' as const, source: 'cart_attr', records: 5 },
      { dayIst: '2026-10-09', channel: 'push', purpose: 'marketing', state: 'granted' as const, source: 'soft_ask', records: 1 },
    ];
    expect(optOutsByDay(rows)).toEqual([
      { day: '2026-10-08', optOuts: 0, optIns: 5 },
      { day: '2026-10-09', optOuts: 2, optIns: 1 },
    ]);
  });
});
