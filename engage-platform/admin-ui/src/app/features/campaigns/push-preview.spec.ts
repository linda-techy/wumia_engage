import { cut, length, site } from './push-preview';

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
});
