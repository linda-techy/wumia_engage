import { parseValue, showValue } from './config-model';

const draft = (over: Partial<{ text: string; flag: boolean; from: string; to: string }>) => ({
  text: '',
  flag: false,
  from: '',
  to: '',
  ...over,
});

describe('config values', () => {
  it('reads whole numbers within the schema, as the server will', () => {
    const cap = { valueType: 'INT' as const, jsonSchema: { minimum: 0, maximum: 2 } };
    expect(parseValue(cap, draft({ text: '2' }))).toEqual({ ok: true, value: 2 });
    expect(parseValue(cap, draft({ text: '3' }))).toEqual({ ok: false, error: 'At most 2' });
    expect(parseValue(cap, draft({ text: '1.5' }))).toEqual({ ok: false, error: 'A whole number' });
  });

  it('reads decimals, booleans, enums and JSON', () => {
    expect(parseValue({ valueType: 'DECIMAL', jsonSchema: { maximum: 50 } }, draft({ text: '5.5' }))).toEqual({ ok: true, value: 5.5 });
    expect(parseValue({ valueType: 'BOOL', jsonSchema: null }, draft({ flag: true }))).toEqual({ ok: true, value: true });
    expect(parseValue({ valueType: 'ENUM', jsonSchema: { enum: ['a', 'b'] } }, draft({ text: 'c' })).ok).toBe(false);
    expect(parseValue({ valueType: 'JSON', jsonSchema: null }, draft({ text: '{"a":1}' }))).toEqual({ ok: true, value: { a: 1 } });
    expect(parseValue({ valueType: 'JSON', jsonSchema: null }, draft({ text: '{a' })).ok).toBe(false);
  });

  it('reads quiet hours as two distinct 24-hour times', () => {
    const q = { valueType: 'TIME_RANGE' as const, jsonSchema: null };
    expect(parseValue(q, draft({ from: '21:00', to: '09:00' }))).toEqual({ ok: true, value: { from: '21:00', to: '09:00' } });
    expect(parseValue(q, draft({ from: '09:00', to: '09:00' })).ok).toBe(false);
    expect(showValue({ from: '21:00', to: '09:00' })).toBe('21:00–09:00');
  });
});
