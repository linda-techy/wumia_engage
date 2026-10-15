import { canErase } from './erase-customer';
import { privacyKindLabel, resultSummary } from './privacy-requests';

describe('privacy', () => {
  it('erases only with a reason and the typed word', () => {
    expect(canErase('Customer emailed on 15 Oct', 'ERASE')).toBe(true);
    expect(canErase('Customer emailed on 15 Oct', 'erase')).toBe(false);
    expect(canErase('asks', 'ERASE')).toBe(false);
  });

  it('names each request in the owner\'s words', () => {
    expect(privacyKindLabel('data_request')).toContain('send the customer their data');
    expect(privacyKindLabel('shop_redact')).toContain('runbook');
  });

  it('summarises erasure counts from the console and from Shopify', () => {
    expect(resultSummary('{"identities": 2, "sends": 5, "orders": 1}')).toBe('Erased 2 profile(s), 5 message(s), 1 order(s)');
    expect(resultSummary('{"people": 1, "erasures": [{"identities": 1, "sends": 3, "orders": 1}], "orders_scrubbed": 1}'))
      .toBe('Erased 1 profile(s), 3 message(s), 2 order(s)');
    expect(resultSummary('{}')).toBe('');
    expect(resultSummary(null)).toBe('');
  });
});
