// GET /api/config shapes, and turning what an operator typed into a config value.

export type ValueType = 'INT' | 'DECIMAL' | 'BOOL' | 'STRING' | 'ENUM' | 'JSON' | 'TIME_RANGE';

export interface ConfigVersion {
  versionId: number;
  selector: string;
  value: unknown;
  effectiveFrom: string;
  effectiveTo: string | null;
  changedBy: string | null;
  approvedBy: string | null;
  reason: string;
}

export interface ConfigProposal {
  proposalId: number;
  selector: string;
  value: unknown;
  effectiveFrom: string | null;
  effectiveTo: string | null;
  reason: string;
  proposedBy: string;
  createdAt: string;
}

export interface ConfigKey {
  key: string;
  scope: 'GLOBAL' | 'CHANNEL' | 'JOURNEY' | 'TEMPLATE';
  valueType: ValueType;
  jsonSchema: { minimum?: number; maximum?: number; enum?: string[] } | null;
  label: string;
  helpText: string | null;
  risk: 'SAFE' | 'GUARDED' | 'CRITICAL';
  minRole: string;
  defaultValue: unknown;
  inForce: ConfigVersion[];
  scheduled: ConfigVersion[];
  pending: ConfigProposal[];
}

/** What the editor holds as text, per control. TIME_RANGE uses from/to; BOOL uses flag. */
export interface Draft {
  text: string;
  flag: boolean;
  from: string;
  to: string;
}

export type Parsed = { ok: true; value: unknown } | { ok: false; error: string };

/**
 * The typed value for a draft, checked as far as the browser can (the
 * server checks again against the same schema, and that check is the one
 * that counts).
 */
export function parseValue(key: Pick<ConfigKey, 'valueType' | 'jsonSchema'>, d: Draft): Parsed {
  const s = key.jsonSchema ?? {};
  const bounds = (n: number): Parsed => {
    if (s.minimum !== undefined && n < s.minimum) return { ok: false, error: `At least ${s.minimum}` };
    if (s.maximum !== undefined && n > s.maximum) return { ok: false, error: `At most ${s.maximum}` };
    return { ok: true, value: n };
  };
  switch (key.valueType) {
    case 'INT': {
      if (!/^-?\d+$/.test(d.text.trim())) return { ok: false, error: 'A whole number' };
      return bounds(Number(d.text.trim()));
    }
    case 'DECIMAL': {
      if (!/^-?\d+(\.\d+)?$/.test(d.text.trim())) return { ok: false, error: 'A number' };
      return bounds(Number(d.text.trim()));
    }
    case 'BOOL':
      return { ok: true, value: d.flag };
    case 'TIME_RANGE': {
      const hhmm = /^([01]\d|2[0-3]):[0-5]\d$/;
      if (!hhmm.test(d.from) || !hhmm.test(d.to)) return { ok: false, error: 'Two times, HH:MM' };
      if (d.from === d.to) return { ok: false, error: 'From and to must differ' };
      return { ok: true, value: { from: d.from, to: d.to } };
    }
    case 'ENUM':
      if (!s.enum?.includes(d.text)) return { ok: false, error: 'Pick one of the options' };
      return { ok: true, value: d.text };
    case 'STRING':
      return d.text.trim() ? { ok: true, value: d.text.trim() } : { ok: false, error: 'Enter a value' };
    case 'JSON':
      try {
        return { ok: true, value: JSON.parse(d.text) };
      } catch {
        return { ok: false, error: 'Not valid JSON' };
      }
  }
}

/** A draft pre-filled from the value in force (or the default). */
export function draftFor(key: ConfigKey, selector = '*'): Draft {
  const current = key.inForce.find((v) => v.selector === selector)?.value ?? key.defaultValue;
  const range = (current ?? {}) as { from?: string; to?: string };
  return {
    text: current === null || current === undefined || typeof current === 'object' ? (key.valueType === 'JSON' ? JSON.stringify(current ?? {}) : '') : String(current),
    flag: current === true,
    from: range.from ?? '',
    to: range.to ?? '',
  };
}

/** A value as one short line: {"from":"21:00","to":"09:00"} → "21:00–09:00". */
export function showValue(v: unknown): string {
  if (v === null || v === undefined) return '—';
  if (typeof v === 'object' && v !== null && 'from' in v && 'to' in v) {
    const r = v as { from: string; to: string };
    return `${r.from}–${r.to}`;
  }
  return typeof v === 'object' ? JSON.stringify(v) : String(v);
}
