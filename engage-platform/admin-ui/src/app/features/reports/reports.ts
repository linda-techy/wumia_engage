import { HttpClient, HttpHeaders, httpResource } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { JourneyLift, SpendReconciliation } from '../../core/api/models';
import { TokenService } from '../../core/auth/token.service';
import { InrPipe, problemDetail } from '../../shared/format';
import { EmptyState, ErrorState, LoadingState } from '../../shared/states';

/** Journey keys in the words staff use. */
export function journeyLabel(key: string): string {
  const names: Record<string, string> = {
    global: 'All marketing (global holdout)',
    checkout_abandon: 'Abandoned checkout',
    cart_recovery: 'Abandoned cart',
    browse_abandon: 'Browsed, did not add',
    price_drop: 'Price drop',
    back_in_stock: 'Back in stock',
    winback: 'Long time no see',
    post_purchase: 'After purchase (review ask)',
    payment_failed: 'Payment failed',
    order_tracking: 'Order updates',
    delivery_ack: 'Delivery replies',
    wa_stop_confirm: 'WhatsApp STOP confirmation',
  };
  return names[key] ?? key;
}

/** "+12.3%" or "—". */
export function pct(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) return '—';
  return `${value > 0 ? '+' : ''}${value.toFixed(1)}%`;
}

/** "4.2×" return on messaging spend, or "—" when nothing was spent. */
export function roiLabel(roi: number | null | undefined): string {
  return roi === null || roi === undefined ? '—' : `${roi.toFixed(1)}×`;
}

/** Last month as YYYY-MM, in IST. */
export function lastMonth(now = new Date()): string {
  const ist = new Date(now.getTime() + 330 * 60_000);
  const d = new Date(Date.UTC(ist.getUTCFullYear(), ist.getUTCMonth() - 1, 1));
  return `${d.getUTCFullYear()}-${String(d.getUTCMonth() + 1).padStart(2, '0')}`;
}

/**
 * Measurement (P7-T03). Does each journey earn more than it costs? Customers
 * held out of a journey (10% by default) get nothing from it; the difference
 * in what the two groups spend is what the journey earned. Below that, our
 * WhatsApp spend against Meta's bill for the month.
 */
@Component({
  selector: 'app-reports',
  imports: [InrPipe, LoadingState, ErrorState, EmptyState],
  template: `
    <header class="mb-6">
      <h1 class="text-2xl font-semibold">Reports</h1>
      <p class="text-sm text-slate-500">What each journey earns against the customers held out of it, and what WhatsApp cost.</p>
    </header>

    <section class="mb-10" aria-labelledby="lift-h">
      <div class="mb-3 flex flex-wrap items-baseline justify-between gap-2">
        <h2 id="lift-h" class="text-lg font-semibold">Journey lift</h2>
        @if (canSeeLift()) {
          <label class="text-sm">Over the last
            <select class="input ml-1" [value]="days()" (change)="setDays($event)">
              <option value="28">4 weeks</option>
              <option value="56">8 weeks</option>
              <option value="91">13 weeks</option>
            </select>
          </label>
        }
      </div>

      @if (!canSeeLift()) {
        <p class="text-sm text-slate-500">An analyst can see journey lift.</p>
      } @else if (lift.isLoading() && !lift.hasValue()) {
        <app-loading />
      } @else if (lift.error()) {
        <app-error [error]="lift.error()" (retry)="lift.reload()" />
      } @else if (lift.hasValue()) {
        @if (lift.value().length === 0) {
          <app-empty text="No holdout yet. It starts with the first marketing message." />
        } @else {
          <div class="overflow-x-auto rounded border border-slate-200 bg-white">
            <table class="w-full text-sm">
              <thead class="bg-slate-50 text-left text-slate-600">
                <tr>
                  <th class="p-2">Journey</th>
                  <th class="p-2 text-right">Messaged / held out</th>
                  <th class="p-2 text-right">Spend per customer</th>
                  <th class="p-2 text-right">Lift (95% range)</th>
                  <th class="p-2 text-right">Earned extra</th>
                  <th class="p-2 text-right">Messaging cost</th>
                  <th class="p-2 text-right">Return</th>
                </tr>
              </thead>
              <tbody>
                @for (r of lift.value(); track r.journey) {
                  <tr class="border-t border-slate-100 align-top">
                    <td class="p-2 font-medium">
                      {{ journey(r.journey) }}
                      @if (!r.enoughData) {
                        <div class="text-xs font-normal text-amber-700">Not enough data yet: {{ r.why }}</div>
                      } @else if (r.significant) {
                        <div class="text-xs font-normal text-emerald-700">A real difference, not chance</div>
                      } @else {
                        <div class="text-xs font-normal text-slate-500">Could still be chance</div>
                      }
                    </td>
                    <td class="p-2 text-right">{{ r.treated }} / {{ r.control }}</td>
                    <td class="p-2 text-right">{{ r.revenuePerTreatedPaise | inr }} vs {{ r.revenuePerControlPaise | inr }}</td>
                    <td class="p-2 text-right">
                      {{ pct(r.liftPct) }}
                      <div class="text-xs text-slate-500">{{ pct(r.liftLowPct) }} to {{ pct(r.liftHighPct) }}</div>
                    </td>
                    <td class="p-2 text-right">
                      {{ r.incrementalPaise | inr }}
                      <div class="text-xs text-slate-500">{{ r.incrementalLowPaise | inr }} to {{ r.incrementalHighPaise | inr }}</div>
                    </td>
                    <td class="p-2 text-right">{{ r.messagingCostPaise | inr }}</td>
                    <td class="p-2 text-right">{{ roi(r.roi) }}</td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
          <p class="mt-2 text-xs text-slate-500">
            Spend counts orders after a customer entered the journey, less refunds, cancelled orders left out.
            Results need 4 weeks and 30 customers in each group before they mean anything.
          </p>
        }
      }
    </section>

    <section aria-labelledby="spend-h">
      <div class="mb-3 flex flex-wrap items-baseline justify-between gap-2">
        <h2 id="spend-h" class="text-lg font-semibold">WhatsApp spend against Meta's bill</h2>
        <label class="text-sm">Month
          <input type="month" class="input ml-1" [value]="month()" (change)="setMonth($event)" />
        </label>
      </div>

      @if (spend.isLoading() && !spend.hasValue()) {
        <app-loading />
      } @else if (spend.error()) {
        <app-error [error]="spend.error()" (retry)="spend.reload()" />
      } @else if (spend.hasValue()) {
        @if (!spend.value().billUploaded) {
          <p class="mb-3 rounded bg-amber-50 p-3 text-sm text-amber-900">
            Meta's bill for {{ spend.value().month }} is not uploaded yet, so only our own count is shown.
          </p>
        }
        @if (spend.value().rows.length === 0) {
          <app-empty text="No WhatsApp spend that month." />
        } @else {
          <table class="w-full rounded border border-slate-200 bg-white text-sm">
            <thead class="bg-slate-50 text-left text-slate-600">
              <tr><th class="p-2">Category</th><th class="p-2 text-right">Our count</th><th class="p-2 text-right">Meta's bill</th><th class="p-2 text-right">Difference</th></tr>
            </thead>
            <tbody>
              @for (r of spend.value().rows; track r.category) {
                <tr class="border-t border-slate-100" [class.bg-red-50]="r.flagged">
                  <td class="p-2 capitalize">{{ r.category }}</td>
                  <td class="p-2 text-right">{{ r.ledgerPaise | inr }}</td>
                  <td class="p-2 text-right">{{ r.metaPaise | inr }}</td>
                  <td class="p-2 text-right" [class.font-semibold]="r.flagged" [class.text-red-800]="r.flagged">
                    {{ pct(r.diffPct) }}{{ r.flagged ? ' (over 1%: check)' : '' }}
                  </td>
                </tr>
              }
            </tbody>
          </table>
        }
      }

      @if (canUpload()) {
        <div class="mt-6 rounded border border-slate-200 bg-white p-4">
          <h3 class="font-semibold">Upload Meta's bill</h3>
          <p class="mb-2 text-sm text-slate-500">
            From WhatsApp Manager, Insights, the month's charges by category. One month per upload, amounts in rupees.
          </p>
          <label class="flex flex-col text-sm">
            <span>CSV</span>
            <textarea class="input h-28 font-mono" [value]="csv()" (input)="setCsv($event)"
                      placeholder="month,category,amount_inr&#10;2026-09,marketing,8650.00&#10;2026-09,utility,2400.00"></textarea>
          </label>
          <div class="mt-2 flex items-center gap-3">
            <button type="button" class="btn btn-primary" (click)="upload()" [disabled]="busy() || !csv().trim()">Upload</button>
            @if (uploadMessage()) { <span class="text-sm text-emerald-700" role="status">{{ uploadMessage() }}</span> }
            @if (uploadError()) { <span class="text-sm text-red-800" role="alert">{{ uploadError() }}</span> }
          </div>
        </div>
      }
    </section>
  `,
})
export default class ReportsPage {
  readonly #http = inject(HttpClient);
  readonly #tokens = inject(TokenService);

  readonly canSeeLift = computed(() => this.#tokens.has('ANALYST'));
  readonly canUpload = computed(() => this.#tokens.has('CONFIG_ADMIN'));
  readonly days = signal(56);
  readonly month = signal(lastMonth());
  readonly lift = httpResource<JourneyLift[]>(() => (this.canSeeLift() ? `/api/reports/lift?days=${this.days()}` : undefined));
  readonly spend = httpResource<SpendReconciliation>(() => `/api/reports/spend-reconciliation?month=${this.month()}`);
  readonly csv = signal('');
  readonly busy = signal(false);
  readonly uploadMessage = signal<string | null>(null);
  readonly uploadError = signal<string | null>(null);

  journey = journeyLabel;
  pct = pct;
  roi = roiLabel;

  setDays(e: Event): void {
    this.days.set(Number((e.target as HTMLSelectElement).value));
  }

  setMonth(e: Event): void {
    const v = (e.target as HTMLInputElement).value;
    if (v) this.month.set(v);
  }

  setCsv(e: Event): void {
    this.csv.set((e.target as HTMLTextAreaElement).value);
  }

  async upload(): Promise<void> {
    this.busy.set(true);
    this.uploadMessage.set(null);
    this.uploadError.set(null);
    try {
      const r = await firstValueFrom(this.#http.post<{ month: string; rows: number }>('/api/reports/meta-billing', this.csv(),
        { headers: new HttpHeaders({ 'Content-Type': 'text/csv' }) }));
      this.uploadMessage.set(`Saved ${r.rows} rows for ${r.month}.`);
      this.csv.set('');
      this.month.set(r.month);
      this.spend.reload();
    } catch (e) {
      this.uploadError.set(problemDetail(e, 'Could not upload the bill.'));
    } finally {
      this.busy.set(false);
    }
  }
}
