// The segment builder's rows ⇄ the server's JSON DSL ({all|any:[...]}, {not}, {field, op, value}).

export interface Field {
  name: string;
  label: string;
  ops: string[];
  valueKind: string; // integer | period | text_list | boolean | rupees | enum:A,B,C
  available: boolean;
  note: string | null;
}

/** One condition as the builder edits it: the value is always text in the box. */
export interface Condition {
  field: string;
  op: string;
  value: string;
  not: boolean;
}

export interface Builder {
  match: 'all' | 'any';
  conditions: Condition[];
}

export type Definition = Record<string, unknown>;

export const OP_LABELS: Record<string, string> = {
  eq: 'is',
  gte: 'at least',
  lte: 'at most',
  gt: 'more than',
  lt: 'less than',
  in: 'is any of',
  is: 'is',
  older_than: 'more than … days ago',
  within: 'within the last … days',
};

/** The value the server expects for what was typed, or an error. */
export function toValue(kind: string, text: string): { ok: true; value: unknown } | { ok: false; error: string } {
  const t = text.trim();
  if (kind === 'integer') return /^\d+$/.test(t) ? { ok: true, value: Number(t) } : { ok: false, error: 'a whole number' };
  if (kind === 'rupees') return /^\d+(\.\d{1,2})?$/.test(t) ? { ok: true, value: Number(t) } : { ok: false, error: 'an amount in ₹' };
  if (kind === 'period') return /^[1-9]\d{0,3}$/.test(t) ? { ok: true, value: `P${t}D` } : { ok: false, error: 'a number of days' };
  if (kind === 'boolean') return t === 'true' || t === 'false' ? { ok: true, value: t === 'true' } : { ok: false, error: 'yes or no' };
  if (kind.startsWith('enum:')) {
    return kind.slice(5).split(',').includes(t) ? { ok: true, value: t } : { ok: false, error: 'pick an option' };
  }
  const items = t.split(',').map((s) => s.trim()).filter(Boolean);
  return items.length ? { ok: true, value: items } : { ok: false, error: 'one or more values, comma-separated' };
}

/** Builder → DSL. Errors name the first bad condition. */
export function toDefinition(b: Builder, fields: Field[]): { ok: true; definition: Definition } | { ok: false; error: string } {
  if (b.conditions.length === 0) return { ok: false, error: 'Add at least one condition' };
  const items: Definition[] = [];
  for (const [i, c] of b.conditions.entries()) {
    const f = fields.find((x) => x.name === c.field);
    if (!f) return { ok: false, error: `Condition ${i + 1}: pick a field` };
    const v = toValue(f.valueKind, c.value);
    if (!v.ok) return { ok: false, error: `Condition ${i + 1} (${f.label}): ${v.error}` };
    const p: Definition = { field: c.field, op: c.op, value: v.value };
    items.push(c.not ? { not: p } : p);
  }
  return { ok: true, definition: { [b.match]: items } };
}

/** DSL → builder, when the definition is one level of all/any (with optional NOTs). Null otherwise. */
export function fromDefinition(d: unknown): Builder | null {
  if (!d || typeof d !== 'object') return null;
  const obj = d as Definition;
  const match = 'all' in obj ? 'all' : 'any' in obj ? 'any' : null;
  const list = match ? obj[match] : [obj];
  if (!Array.isArray(list)) return null;
  const conditions: Condition[] = [];
  for (const item of list) {
    const not = !!item && typeof item === 'object' && 'not' in item;
    const p = (not ? (item as Definition)['not'] : item) as Definition | undefined;
    if (!p || typeof p !== 'object' || !('field' in p)) return null;
    conditions.push({ field: String(p['field']), op: String(p['op']), value: textOf(p['value']), not });
  }
  return { match: match ?? 'all', conditions };
}

function textOf(v: unknown): string {
  if (Array.isArray(v)) return v.join(', ');
  if (typeof v === 'string') {
    const days = /^P(\d+)D$/.exec(v);
    return days ? days[1] : v;
  }
  return v === undefined || v === null ? '' : String(v);
}
