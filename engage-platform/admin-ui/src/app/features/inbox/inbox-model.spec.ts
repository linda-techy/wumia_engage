import { timeLeft, windowState } from './inbox-model';

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
