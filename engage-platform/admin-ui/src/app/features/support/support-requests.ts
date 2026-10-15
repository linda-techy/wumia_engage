import { HttpClient, httpResource } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { SupportRequest } from '../../core/api/models';
import { TokenService } from '../../core/auth/token.service';
import { IstPipe, problemDetail } from '../../shared/format';
import { EmptyState, ErrorState, LoadingState } from '../../shared/states';

/** What a request is, in staff's words. */
export function kindLabel(kind: string): string {
  switch (kind) {
    case 'delivery': return 'Failed delivery: customer replied';
    case 'after_delivery': return 'Needs help after delivery';
    default: return kind;
  }
}

/**
 * Customers' replies staff act on by hand: a new delivery time or address
 * after a failed delivery (update it in Shiprocket), or a "Need help" after
 * delivery. Reading a reply is logged like any customer detail.
 */
@Component({
  selector: 'app-support-requests',
  imports: [IstPipe, LoadingState, ErrorState, EmptyState, RouterLink],
  template: `
    <header class="mb-6 flex items-baseline justify-between">
      <div>
        <h1 class="text-2xl font-semibold">Support requests</h1>
        <p class="text-sm text-slate-500">
          Customer replies to act on. For a failed delivery, update the parcel in Shiprocket, then mark it done.
        </p>
      </div>
      <button type="button" class="btn" (click)="data.reload()">Refresh</button>
    </header>

    <div class="mb-4 flex gap-2" role="tablist" aria-label="Status">
      <button type="button" role="tab" class="btn" [class.btn-primary]="status() === 'open'"
              [attr.aria-selected]="status() === 'open'" (click)="status.set('open')">Open</button>
      <button type="button" role="tab" class="btn" [class.btn-primary]="status() === 'done'"
              [attr.aria-selected]="status() === 'done'" (click)="status.set('done')">Done</button>
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
        <app-empty [text]="status() === 'open' ? 'Nothing waiting. Replies to failed deliveries appear here.' : 'Nothing marked done yet.'" />
      } @else {
        <ul class="flex flex-col gap-3">
          @for (r of data.value(); track r.id) {
            <li class="rounded border border-slate-200 bg-white p-4">
              <div class="flex flex-wrap items-baseline justify-between gap-2">
                <div>
                  <span class="font-semibold">{{ kind(r.kind) }}</span>
                  <span class="text-sm text-slate-500"> · {{ r.createdAt | ist }}</span>
                </div>
                <div class="text-sm">
                  @if (r.orderNumber) {
                    Order <a [routerLink]="['/customers', r.identityId]" class="underline">{{ r.orderNumber }}</a>
                  }
                  @if (r.awb) { <span class="text-slate-500"> · AWB <span class="font-mono">{{ r.awb }}</span> ({{ r.courier ?? 'courier' }})</span> }
                </div>
              </div>

              @if (replies()[r.id]; as text) {
                <blockquote class="mt-3 whitespace-pre-line rounded bg-slate-50 p-3 text-sm">{{ text }}</blockquote>
              } @else if (r.hasText && canAct()) {
                <button type="button" class="btn mt-3" (click)="showReply(r.id)" [disabled]="busy() === r.id">
                  Show reply{{ r.messages > 1 ? ' (' + r.messages + ' messages)' : '' }}
                </button>
                <p class="mt-1 text-xs text-slate-500">Logged as a look at customer details; 50 a day.</p>
              } @else if (r.hasText) {
                <p class="mt-3 text-sm text-slate-500">An analyst can read the reply.</p>
              }

              @if (r.status === 'open' && canAct()) {
                <div class="mt-3 flex flex-wrap items-end gap-2">
                  <label class="flex flex-col text-sm">
                    <span>Note (optional)</span>
                    <input class="input w-80" [id]="'note-' + r.id" maxlength="1000"
                           placeholder="e.g. Rescheduled in Shiprocket for 16 Oct"
                           [value]="notes()[r.id] ?? ''" (input)="setNote(r.id, $event)" />
                  </label>
                  <button type="button" class="btn btn-primary" (click)="resolve(r.id)" [disabled]="busy() === r.id">
                    Mark done
                  </button>
                </div>
              }
              @if (r.status === 'done') {
                <p class="mt-3 text-sm text-slate-600">
                  Done by {{ r.resolvedBy ?? '—' }} · {{ r.resolvedAt | ist }}{{ r.note ? ' · ' + r.note : '' }}
                </p>
              }
            </li>
          }
        </ul>
      }
    }
  `,
})
export default class SupportRequestsPage {
  readonly #http = inject(HttpClient);
  readonly #tokens = inject(TokenService);

  readonly status = signal<'open' | 'done'>('open');
  readonly data = httpResource<SupportRequest[]>(() => `/api/support-requests?status=${this.status()}`);
  readonly canAct = computed(() => this.#tokens.has('ANALYST'));
  readonly replies = signal<Record<number, string>>({});
  readonly notes = signal<Record<number, string>>({});
  readonly busy = signal<number | null>(null);
  readonly actionError = signal<string | null>(null);

  kind(k: string): string {
    return kindLabel(k);
  }

  setNote(id: number, e: Event): void {
    const value = (e.target as HTMLInputElement).value;
    this.notes.update((n) => ({ ...n, [id]: value }));
  }

  async showReply(id: number): Promise<void> {
    this.busy.set(id);
    this.actionError.set(null);
    try {
      const r = await firstValueFrom(this.#http.post<{ id: number; text: string }>(`/api/support-requests/${id}/reply`, {}));
      this.replies.update((m) => ({ ...m, [id]: r.text }));
    } catch (e) {
      this.actionError.set(problemDetail(e, 'Could not show the reply.'));
    } finally {
      this.busy.set(null);
    }
  }

  async resolve(id: number): Promise<void> {
    this.busy.set(id);
    this.actionError.set(null);
    try {
      const note = (this.notes()[id] ?? '').trim();
      await firstValueFrom(this.#http.post(`/api/support-requests/${id}/resolve`, note ? { note } : {}));
      this.data.reload();
    } catch (e) {
      this.actionError.set(problemDetail(e, 'Could not mark it done.'));
    } finally {
      this.busy.set(null);
    }
  }
}
