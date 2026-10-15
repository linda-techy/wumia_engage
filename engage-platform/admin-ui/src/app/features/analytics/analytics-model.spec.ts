import { blockTotals, dailyBars, lastDays, rate, reasonLabel, returnPerRupee, spendTotals } from './analytics-model';

describe('analytics model', () => {
  it('writes rates and returns plainly', () => {
    expect(rate(25, 200)).toBe('12.5%');
    expect(rate(1, 0)).toBe('—');
    expect(returnPerRupee({ revenuePaise: 420000, costPaise: 100000 })).toBe('₹4.2');
    expect(returnPerRupee({ revenuePaise: 5000, costPaise: 0 })).toBe('—');
  });

  it('gives the last N days in IST', () => {
    expect(lastDays(7, new Date('2026-10-15T20:00:00Z'))).toEqual({ from: '2026-10-10', to: '2026-10-16' });
  });

  it('adds channels up per day and fills days without sends with zero', () => {
    const days = [
      { day: '2026-10-01', channel: 'push', sent: 10, delivered: 9, clicked: 2, orders: 1, revenuePaise: 100 },
      { day: '2026-10-01', channel: 'whatsapp', sent: 5, delivered: 5, clicked: 0, orders: 1, revenuePaise: 200 },
      { day: '2026-10-03', channel: 'push', sent: 4, delivered: 4, clicked: 1, orders: 0, revenuePaise: 0 },
    ];
    expect(dailyBars(days, 'sent', '2026-10-01', '2026-10-03')).toEqual([
      { label: '2026-10-01', value: 15 }, { label: '2026-10-02', value: 0 }, { label: '2026-10-03', value: 4 },
    ]);
    expect(dailyBars(days, 'revenuePaise', '2026-10-01', '2026-10-01')).toEqual([{ label: '2026-10-01', value: 300 }]);
  });

  it('totals spend and blocks over the range, largest first', () => {
    expect(spendTotals([
      { day: 'a', channel: 'whatsapp', category: 'marketing', messages: 2, paise: 172 },
      { day: 'b', channel: 'whatsapp', category: 'marketing', messages: 1, paise: 86 },
      { day: 'b', channel: 'whatsapp', category: 'utility', messages: 3, paise: 36 },
    ])).toEqual([
      { channel: 'whatsapp', category: 'marketing', messages: 3, paise: 258 },
      { channel: 'whatsapp', category: 'utility', messages: 3, paise: 36 },
    ]);
    expect(blockTotals([
      { day: 'a', channel: 'push', status: 'blocked', reason: 'QUIET_HOURS', sends: 2 },
      { day: 'b', channel: 'push', status: 'blocked', reason: 'QUIET_HOURS', sends: 3 },
      { day: 'b', channel: 'push', status: 'deferred', reason: 'QUIET_HOURS', sends: 9 },
    ])[0]).toEqual({ reason: 'QUIET_HOURS', status: 'deferred', sends: 9 });
  });

  it('names policy reasons in plain words', () => {
    expect(reasonLabel('QUIET_HOURS')).toBe('Quiet hours (night)');
    expect(reasonLabel('SOMETHING_NEW')).toBe('something new');
  });
});
