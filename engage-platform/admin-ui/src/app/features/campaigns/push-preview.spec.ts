import { countdownDigits, cut, length, site, timeLeftText } from './push-preview';

describe('push preview', () => {
  it('counts an emoji as one character', () => {
    expect(length('Dressed for the festivities ✨')).toBe(29);
  });

  it('cuts past the limit with an ellipsis, like the send', () => {
    expect(cut('Short title', 40)).toBe('Short title');
    const long = 'The Onam 2026 edit: kasavu sarees and festive kurtas';
    const shown = cut(long, 40);
    expect(length(shown)).toBe(40);
    expect(shown.endsWith('…')).toBe(true);
  });

  it('names the site from the click-through link', () => {
    expect(site('https://www.wumika.com/collections/onam')).toBe('wumika.com');
    expect(site('not a url')).toBe('your store');
    expect(site(null)).toBe('your store');
  });

  it('says the time left the way the server will', () => {
    const m = 60_000, h = 60 * m;
    expect(timeLeftText(3 * 24 * h)).toBe('3 days');
    expect(timeLeftText(29 * h)).toBe('1 day 5h');
    expect(timeLeftText(24 * h)).toBe('1 day');
    expect(timeLeftText(5 * h + 12 * m)).toBe('5h 12m');
    expect(timeLeftText(45 * m)).toBe('45 min');
    expect(timeLeftText(10_000)).toBe('1 min');
  });

  it('draws HH:MM:SS, and DD:HH:MM from 100 hours', () => {
    expect(countdownDigits((2 * 3600 + 14 * 60 + 33) * 1000)).toEqual({ groups: ['02', '14', '33'], units: ['HRS', 'MIN', 'SEC'] });
    expect(countdownDigits((5 * 86400 + 3 * 3600 + 7 * 60) * 1000)).toEqual({ groups: ['05', '03', '07'], units: ['DAYS', 'HRS', 'MIN'] });
  });
});
