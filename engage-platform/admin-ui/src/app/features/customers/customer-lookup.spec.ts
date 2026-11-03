import { FILTERS, range } from './customer-lookup';

describe('customer list', () => {
  it('says which rows a page shows', () => {
    expect(range({ total: 1108, page: 0, pageSize: 50, rows: [] })).toBe('1–50 of 1,108');
    expect(range({ total: 1108, page: 22, pageSize: 50, rows: [] })).toBe('1,101–1,108 of 1,108');
    expect(range({ total: 0, page: 0, pageSize: 50, rows: [] })).toBe('0 of 0');
  });

  it('offers the four filters the API knows', () => {
    expect(FILTERS.map((f) => f.key)).toEqual(['all', 'buyers', 'whatsapp', 'reachable']);
  });
});
