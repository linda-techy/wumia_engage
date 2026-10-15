import { journeyLabel, lastMonth, pct, roiLabel } from './reports';

describe('reports', () => {
  it('names journeys in staff words', () => {
    expect(journeyLabel('checkout_abandon')).toBe('Abandoned checkout');
    expect(journeyLabel('winback')).toBe('Long time no see');
    expect(journeyLabel('something_new')).toBe('something_new');
  });

  it('writes lift and return plainly', () => {
    expect(pct(12.345)).toBe('+12.3%');
    expect(pct(-4.17)).toBe('-4.2%');
    expect(pct(null)).toBe('—');
    expect(roiLabel(4.25)).toBe('4.3×');
    expect(roiLabel(null)).toBe('—');
  });

  it('takes last month in IST, even late on the last evening of a month in UTC', () => {
    expect(lastMonth(new Date('2026-10-15T10:00:00Z'))).toBe('2026-09');
    expect(lastMonth(new Date('2026-12-31T20:00:00Z'))).toBe('2026-12'); // already 1 Jan in IST
    expect(lastMonth(new Date('2026-01-10T10:00:00Z'))).toBe('2025-12');
  });
});
