import { haltConfirmWord, haltLabel } from './halt-rules';

describe('halt rules', () => {
  it('asks for the channel or journey name, else the scope', () => {
    expect(haltConfirmWord('marketing', '*')).toBe('marketing');
    expect(haltConfirmWord('channel', 'whatsapp')).toBe('whatsapp');
    expect(haltConfirmWord('channel', '*')).toBe('channel');
    expect(haltConfirmWord('journey', 'cart_abandon')).toBe('cart_abandon');
  });

  it('labels halts plainly', () => {
    expect(haltLabel({ scope: 'marketing', selector: '*' })).toBe('All marketing');
    expect(haltLabel({ scope: 'channel', selector: 'push' })).toBe('Channel: push');
    expect(haltLabel({ scope: 'channel', selector: '*' })).toBe('Channel: every channel');
    expect(haltLabel({ scope: 'journey', selector: 'cart_abandon' })).toBe('Journey: cart_abandon');
  });
});
