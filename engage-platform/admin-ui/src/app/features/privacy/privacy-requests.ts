import { HttpClient, httpResource } from '@angular/common/http';
import { Component, inject, input, linkedSignal, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { PrivacyRequest } from '../../core/api/models';
import { IstPipe, problemDetail } from '../../shared/format';
import { EmptyState, ErrorState, LoadingState } from '../../shared/states';

/** What a request is and what to do about it, in the Owner's words. */
export function privacyKindLabel(kind: string): string {
  switch (kind) {
    case 'data_request': return 'Data request (Shopify): send the customer their data';
    case 'customer_redact': return 'Deletion (Shopify): done automatically';
    case 'shop_redact': return 'Store removed the app (Shopify): delete all data by the runbook';
    case 'erase': return 'Erased from the console';
    default: return kind;
  }
}

/** "3 people, 12 messages, 2 orders" from the erasure counts. */
export function resultSummary(json: string | null): string {
  if (!json || json === '{}') return '';
  try {
    const r = JSON.parse(json) as Record<string, unknown>;
    const all = Array.isArray(r['erasures']) ? (r['erasures'] as Record<string, number>[]) : [r as Record<string, number>];
    const sum = (k: string) => all.reduce((n, e) => n + (Number(e[k]) || 0), 0);
    const parts = [`${sum('identities')} profile(s)`, `${sum('sends')} message(s)`, `${sum('orders') + (Number(r['orders_scrubbed']) || 0)} order(s)`];
    return `Erased ${parts.join(', ')}`;
  } catch {
    return '';
  }
}

/** A JSON file the browser saves; nothing is kept on the server. */
function download(name: string, data: unknown): void {
  const url = URL.createObjectURL(new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' }));
  const a = document.createElement('a');
  a.href = url;
  a.download = name;
  a.click();
  URL.revokeObjectURL(url);
}

/**
 * Privacy requests (OWNER): Shopify's data requests to answer within 30 days,
 * deletions it asked for (done automatically), a store-removal notice, and
 * erasures from the console.
 */
@Component({
  selector: 'app-privacy-requests',
  imports: [IstPipe, LoadingState, ErrorState, EmptyState],
  template: `
    <header class="mb-6 flex items-baseline justify-between">
      <div>
        <h1 class="text-2xl font-semibold">Privacy requests</h1>
        <p class="text-sm text-slate-500">Answer data requests within 30 days. To erase one customer, open them in Customers.</p>
      </div>
      <button type="button" class="btn" (click)="data.reload()">Refresh</button>
    </header>

    <div class="mb-4 flex gap-2" role="tablist" aria-label="Status">
      <button type="button" role="tab" class="btn" [class.btn-primary]="tab() === 'open'"
              [attr.aria-selected]="tab() === 'open'" (click)="tab.set('open')">Open</button>
      <button type="button" role="tab" class="btn" [class.btn-primary]="tab() === 'done'"
              [attr.aria-selected]="tab() === 'done'" (click)="tab.set('done')">Done</button>
    </div>

    @if (actionError()) {
      <p class="mb-4 rounded bg-red-50 p-3 text-sm text-red-800" role="alert">{{ actionError() }}</p>
    }

    @if (data.isLoading() && !data.hasValue()) {
      <app-loading />
    } @else if (data.error()) {
      <app-error [error]="data.error()" (retry)="data.reload()" />
    } @else if (data.hasValue()) {
      @if (data.value().length === 0) {
        <app-empty [text]="tab() === 'open' ? 'Nothing to answer.' : 'No requests handled yet.'" />
      } @else {
        <ul class="flex flex-col gap-3">
          @for (r of data.value(); track r.id) {
            <li class="rounded border border-slate-200 bg-white p-4">
              <div class="flex flex-wrap items-baseline justify-between gap-2">
                <span class="font-semibold">{{ kind(r.kind) }}</span>
                <span class="text-sm text-slate-500">#{{ r.id }} · {{ r.createdAt | ist }}{{ r.externalRef ? ' · Shopify request ' + r.externalRef : '' }}</span>
              </div>
              @if (r.reason) { <p class="mt-1 text-sm">Reason: {{ r.reason }}{{ r.createdBy ? ' (' + r.createdBy + ')' : '' }}</p> }
              @if (summary(r.result); as s) { <p class="mt-1 text-sm text-slate-600">{{ s }}</p> }

              @if (r.kind === 'shop_redact' && r.status === 'open') {
                <p class="mt-2 text-sm">The store uninstalled Engage 48 hours ago. Follow "Shop redact" in the operations runbook, then mark done.</p>
              }
              @if (r.status === 'open') {
                <div class="mt-3 flex flex-wrap items-end gap-2">
                  @if (r.kind === 'data_request') {
                    <button type="button" class="btn" (click)="report(r.id)" [disabled]="busy() === r.id">Download report</button>
                  }
                  <label class="flex flex-col text-sm">
                    <span>Note</span>
                    <input class="input w-80" maxlength="1000" placeholder="e.g. Report sent to the store owner on 16 Oct"
                           [value]="notes()[r.id] ?? ''" (input)="setNote(r.id, $event)" />
                  </label>
                  <button type="button" class="btn btn-primary" (click)="resolve(r.id)" [disabled]="busy() === r.id">Mark done</button>
                </div>
                @if (r.kind === 'data_request') {
                  <p class="mt-1 text-xs text-slate-500">The report is built now and saved by your browser; it is logged as a look at the customer's details. Marking done forgets who asked.</p>
                }
              } @else {
                <p class="mt-2 text-sm text-slate-600">Done{{ r.resolvedBy ? ' by ' + r.resolvedBy : '' }} · {{ r.resolvedAt | ist }}{{ r.note ? ' · ' + r.note : '' }}</p>
              }
            </li>
          }
        </ul>
      }
    }
  `,
})
export default class PrivacyRequestsPage {
  readonly #http = inject(HttpClient);

  /** ?status=done after an erase. */
  readonly status = input<string>();
  readonly tab = linkedSignal<'open' | 'done'>(() => (this.status() === 'done' ? 'done' : 'open'));
  readonly data = httpResource<PrivacyRequest[]>(() => `/api/privacy-requests?status=${this.tab()}`);
  readonly notes = signal<Record<number, string>>({});
  readonly busy = signal<number | null>(null);
  readonly actionError = signal<string | null>(null);

  kind = privacyKindLabel;
  summary = resultSummary;

  setNote(id: number, e: Event): void {
    const value = (e.target as HTMLInputElement).value;
    this.notes.update((n) => ({ ...n, [id]: value }));
  }

  async report(id: number): Promise<void> {
    this.busy.set(id);
    this.actionError.set(null);
    try {
      const r = await firstValueFrom(this.#http.post(`/api/privacy-requests/${id}/report`, {}));
      download(`data-request-${id}.json`, r);
    } catch (e) {
      this.actionError.set(problemDetail(e, 'Could not build the report.'));
    } finally {
      this.busy.set(null);
    }
  }

  async resolve(id: number): Promise<void> {
    this.busy.set(id);
    this.actionError.set(null);
    try {
      const note = (this.notes()[id] ?? '').trim();
      await firstValueFrom(this.#http.post(`/api/privacy-requests/${id}/resolve`, note ? { note } : {}));
      this.data.reload();
    } catch (e) {
      this.actionError.set(problemDetail(e, 'Could not mark it done.'));
    } finally {
      this.busy.set(null);
    }
  }
}
