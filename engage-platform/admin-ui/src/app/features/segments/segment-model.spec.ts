import { Field, fromDefinition, toDefinition } from './segment-model';

const fields: Field[] = [
  { name: 'bought_product_type', label: 'Bought product type', ops: ['in'], valueKind: 'text_list', available: true, note: null },
  { name: 'last_order_at', label: 'Last order', ops: ['older_than', 'within'], valueKind: 'period', available: true, note: null },
  { name: 'wa_marketing', label: 'WhatsApp marketing', ops: ['is'], valueKind: 'boolean', available: true, note: null },
  { name: 'orders_count', label: 'Orders', ops: ['gte'], valueKind: 'integer', available: true, note: null },
];

describe('segment builder', () => {
  it('builds the phase-6 example from rows', () => {
    const r = toDefinition(
      {
        match: 'all',
        conditions: [
          { field: 'bought_product_type', op: 'in', value: 'kurta, ethnic set', not: false },
          { field: 'last_order_at', op: 'older_than', value: '60', not: false },
          { field: 'wa_marketing', op: 'is', value: 'true', not: true },
        ],
      },
      fields,
    );
    expect(r).toEqual({
      ok: true,
      definition: {
        all: [
          { field: 'bought_product_type', op: 'in', value: ['kurta', 'ethnic set'] },
          { field: 'last_order_at', op: 'older_than', value: 'P60D' },
          { not: { field: 'wa_marketing', op: 'is', value: true } },
        ],
      },
    });
  });

  it('names the first bad condition', () => {
    const r = toDefinition({ match: 'any', conditions: [{ field: 'orders_count', op: 'gte', value: 'two', not: false }] }, fields);
    expect(r).toEqual({ ok: false, error: 'Condition 1 (Orders): a whole number' });
    expect(toDefinition({ match: 'all', conditions: [] }, fields).ok).toBe(false);
  });

  it('reads one level back into rows, and refuses deeper nesting', () => {
    expect(fromDefinition({ any: [{ field: 'last_order_at', op: 'within', value: 'P30D' }, { not: { field: 'wa_marketing', op: 'is', value: true } }] }))
      .toEqual({
        match: 'any',
        conditions: [
          { field: 'last_order_at', op: 'within', value: '30', not: false },
          { field: 'wa_marketing', op: 'is', value: 'true', not: true },
        ],
      });
    expect(fromDefinition({ all: [{ any: [{ field: 'x', op: 'in', value: ['a'] }] }] })).toBeNull();
    expect(fromDefinition({ field: 'state', op: 'in', value: ['KL'] })?.conditions[0].value).toBe('KL');
  });
});
