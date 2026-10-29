import {
  attention, blockReasons, budgetPct, change, Dashboard, journeyRows, optInsByDay, rate, roi, sendTotals, since, tierLimit, tierText,
} from './dashboard-model';

describe('dashboard model', () => {
  it('fills the budget bar and caps it at 100', () => {
    expect(budgetPct({ spentPaise: 51600, budgetPaise: 200000 })).toBe(26);
    expect(budgetPct({ spentPaise: 300000, budgetPaise: 200000 })).toBe(100);
    expect(budgetPct({ spentPaise: 100, budgetPaise: null })).toBeNull();
  });

  it('sums block reasons across channels in words, a switched-off channel marked as expected', () => {
    const rows = [
      { channel: 'push', category: 'marketing', status: 'blocked', reason: 'FREQUENCY_CAP', sends: 3 },
      { channel: 'whatsapp', category: 'marketing', status: 'blocked', reason: 'FREQUENCY_CAP', sends: 4 },
      { channel: 'sms', category: 'utility', status: 'blocked', reason: 'CHANNEL_HALTED', sends: 2 },
      { channel: 'push', category: 'utility', status: 'sent', reason: null, sends: 9 },
    ];
    expect(blockReasons(rows)).toEqual([
      { reason: 'FREQUENCY_CAP', label: 'Already messaged enough', status: 'blocked', sends: 7, expected: false },
      { reason: 'CHANNEL_HALTED', label: 'Channel halted', status: 'blocked', sends: 2, expected: true },
    ]);
    expect(sendTotals(rows)).toEqual({ sent: 9, blocked: 9, deferred: 0, failed: 0 });
  });

  it('counts opt-ins per day by channel, oldest first', () => {
    expect(optInsByDay([
      { dayIst: '2026-10-29', channel: 'whatsapp', optedIn: 2, optedOut: 1 },
      { dayIst: '2026-10-28', channel: 'push', optedIn: 1, optedOut: 0 },
      { dayIst: '2026-10-29', channel: 'push', optedIn: 3, optedOut: 0 },
    ])).toEqual([
      { day: '2026-10-28', whatsapp: 0, push: 1, out: 0 },
      { day: '2026-10-29', whatsapp: 2, push: 3, out: 1 },
    ]);
  });

  it('works out rates, return on spend and the change on the period before', () => {
    expect(rate(1, 3)).toBe('33%');
    expect(rate(1, 0)).toBe('—');
    expect(roi(149900, 8600)).toBeCloseTo(17.43, 1);
    expect(roi(149900, 0)).toBeNull();
    expect(change(150, 100)).toBe(50);
    expect(change(150, 0)).toBeNull();
  });

  it('reads Meta tiers', () => {
    expect(tierLimit('TIER_250')).toBe(250);
    expect(tierLimit('TIER_UNLIMITED')).toBeNull();
    expect(tierText('TIER_1K')).toBe('1,000 people a day');
    expect(tierText('TIER_UNLIMITED')).toBe('No limit');
  });

  it('leaves inbox replies and idle journeys out of journey health', () => {
    const j = (intentKey: string, entered24h: number) => ({ intentKey, entered24h, succeeded24h: 0, exhausted24h: 0, failed24h: 0, stalled: 0 });
    expect(journeyRows([j('inbox_staff_reply', 3), j('welcome', 0), j('checkout_abandon', 5)]).map((r) => r.intentKey))
      .toEqual(['checkout_abandon']);
  });

  it('lists what needs attention, worst first', () => {
    const now = Date.parse('2026-10-30T10:00:00Z');
    const d = {
      waNumber: [{ displayNumber: '+91 73061 40086', verifiedName: 'Wumika', quality: 'GREEN', messagingTier: 'TIER_250', used24h: 230 }],
      waTemplateMismatches: [],
      spendToday: [],
      journeys: [],
      sends24h: [{ channel: 'sms', category: 'utility', status: 'blocked', reason: 'CHANNEL_HALTED', sends: 2 }],
      inbox: { waiting: 2, oldestWaitingAt: '2026-10-30T07:00:00Z', unread: 2 },
    } as unknown as Dashboard;
    const a = attention(d, now);
    expect(a.map((x) => x.tone)).toEqual(['warn', 'warn', 'info']);
    expect(a[0].text).toContain('230 of 250');
    expect(a[1].text).toContain('oldest 3 h');
    expect(a[2].text).toContain('SMS is switched off');
    expect(since('2026-10-30T09:25:00Z', now)).toBe('35 min');
  });
});
