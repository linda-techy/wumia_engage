import { kindLabel } from './support-requests';

describe('support requests', () => {
  it('names each kind in staff words', () => {
    expect(kindLabel('delivery')).toBe('Failed delivery: customer replied');
    expect(kindLabel('after_delivery')).toBe('Needs help after delivery');
    expect(kindLabel('other')).toBe('other');
  });
});
