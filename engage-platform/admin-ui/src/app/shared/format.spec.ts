import { age, ist, problemDetail, rupees } from './format';

describe('format', () => {
  it('formats paise as rupees exactly as Paise.toRupeeString does', () => {
    expect(rupees(149900)).toBe('₹1,499');
    expect(rupees(1249950)).toBe('₹12,499.50');
    expect(rupees(14999900)).toBe('₹1,49,999');
    expect(rupees(17200000)).toBe('₹1,72,000');
    expect(rupees(1234567890)).toBe('₹1,23,45,678.90');
    expect(rupees(5)).toBe('₹0.05');
    expect(rupees(0)).toBe('₹0');
    expect(rupees(-86)).toBe('-₹0.86');
    expect(rupees(null)).toBe('—');
  });

  it('shows instants in IST whatever the browser zone', () => {
    expect(ist('2026-09-30T06:30:00Z')).toContain('12:00');
    expect(ist(null)).toBe('—');
  });

  it('says ages briefly', () => {
    expect(age(45)).toBe('45 s');
    expect(age(185)).toBe('3 m 5 s');
    expect(age(3 * 3600 + 240)).toBe('3 h 4 m');
    expect(age(0)).toBe('—');
  });

  it('shows the problem+json detail, or a plain line when the API is unreachable', () => {
    expect(problemDetail({ status: 403, error: { detail: 'requires ANALYST' } })).toBe('requires ANALYST');
    expect(problemDetail({ status: 0 })).toBe('The console cannot reach admin-api.');
    expect(problemDetail({ status: 500 }, 'fallback')).toBe('fallback');
  });
});
