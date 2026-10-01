import { Pipe, PipeTransform } from '@angular/core';

const INR = new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 2 });
const IST = new Intl.DateTimeFormat('en-IN', {
  timeZone: 'Asia/Kolkata',
  day: '2-digit',
  month: 'short',
  year: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
});

/** Paise (integer) → "₹1,499.00", Indian grouping. Money never travels as a float. */
export function rupees(paise: number | null | undefined): string {
  if (paise === null || paise === undefined) return '—';
  return INR.format(Number(paise) / 100);
}

/** ISO instant → "30 Sept 2026, 06:30 pm" in IST, whatever the browser's zone. */
export function ist(value: string | null | undefined): string {
  if (!value) return '—';
  const d = new Date(value);
  return Number.isNaN(d.getTime()) ? String(value) : IST.format(d);
}

/** Seconds → "3 h 4 m". */
export function age(seconds: number | null | undefined): string {
  if (!seconds) return '—';
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = Math.floor(seconds % 60);
  return h ? `${h} h ${m} m` : m ? `${m} m ${s} s` : `${s} s`;
}

@Pipe({ name: 'inr' })
export class InrPipe implements PipeTransform {
  transform(paise: number | null | undefined): string {
    return rupees(paise);
  }
}

@Pipe({ name: 'ist' })
export class IstPipe implements PipeTransform {
  transform(value: string | null | undefined): string {
    return ist(value);
  }
}

@Pipe({ name: 'age' })
export class AgePipe implements PipeTransform {
  transform(seconds: number | null | undefined): string {
    return age(seconds);
  }
}

/** The problem+json "detail" of an HttpErrorResponse, else a plain fallback. */
export function problemDetail(err: unknown, fallback = 'Something went wrong. Try again.'): string {
  const e = err as { error?: { detail?: string }; status?: number } | null;
  if (e?.status === 0) return 'The console cannot reach admin-api.';
  return e?.error?.detail ?? fallback;
}
