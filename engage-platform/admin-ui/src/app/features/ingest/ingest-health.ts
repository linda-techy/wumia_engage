import { httpResource } from '@angular/common/http';
import { Component, computed } from '@angular/core';
import { IngestHealth } from '../../core/api/models';
import { AgePipe, IstPipe } from '../../shared/format';
import { EmptyState, ErrorState, LoadingState } from '../../shared/states';

/** The webhook inbox: what arrived per source and topic, and what is stuck. */
@Component({
  selector: 'app-ingest-health',
  imports: [AgePipe, IstPipe, LoadingState, ErrorState, EmptyState],
  template: `
    <header class="mb-6 flex items-baseline justify-between">
      <div>
        <h1 class="text-2xl font-semibold">Ingest health</h1>
        <p class="text-sm text-slate-500">Webhooks from Shopify, Razorpay and the courier, as the inbox holds them.</p>
      </div>
      <button type="button" class="btn" (click)="health.reload()">Refresh</button>
    </header>

    @if (health.isLoading() && !health.hasValue()) {
      <app-loading />
    } @else if (health.error()) {
      <app-error [error]="health.error()" (retry)="health.reload()" />
    } @else if (health.hasValue()) {
      <section class="mb-8 grid grid-cols-4 gap-4">
        <div class="card"><p class="label">Received</p><p class="stat">{{ totals().total }}</p></div>
        <div class="card"><p class="label">Waiting</p><p class="stat">{{ totals().pending }}</p></div>
        <div class="card"><p class="label">Failing</p><p class="stat" [class.text-amber-700]="totals().failed">{{ totals().failed }}</p></div>
        <div class="card"><p class="label">Gave up (needs a person)</p><p class="stat" [class.text-red-700]="totals().dead">{{ totals().dead }}</p></div>
      </section>

      <h2 class="mb-2 font-semibold">By source and topic</h2>
      @if (health.value().topics.length === 0) {
        <app-empty text="No webhooks received yet." />
      } @else {
        <table class="table mb-8">
          <thead><tr><th>Source</th><th>Topic</th><th class="num">Total</th><th class="num">Waiting</th><th class="num">Failing</th><th class="num">Gave up</th><th>Oldest waiting</th><th>Last received</th></tr></thead>
          <tbody>
            @for (t of health.value().topics; track t.source + t.topic) {
              <tr>
                <td>{{ t.source }}</td>
                <td class="font-mono text-xs">{{ t.topic }}</td>
                <td class="num">{{ t.total }}</td>
                <td class="num">{{ t.pending }}</td>
                <td class="num" [class.text-amber-700]="t.failed">{{ t.failed }}</td>
                <td class="num" [class.text-red-700]="t.dead">{{ t.dead }}</td>
                <td>{{ t.oldestPendingAgeSeconds | age }}</td>
                <td>{{ t.lastReceivedAt | ist }}</td>
              </tr>
            }
          </tbody>
        </table>
      }

      <h2 class="mb-2 font-semibold">Stuck items</h2>
      @if (health.value().stuck.length === 0) {
        <app-empty text="Nothing is stuck." />
      } @else {
        <table class="table">
          <thead><tr><th>Received</th><th>Source</th><th>Topic</th><th class="num">Attempts</th><th>Last error</th><th>Next try</th></tr></thead>
          <tbody>
            @for (s of health.value().stuck; track s.source + s.deliveryId) {
              <tr>
                <td>{{ s.receivedAt | ist }}</td>
                <td>{{ s.source }}</td>
                <td class="font-mono text-xs">{{ s.topic }}</td>
                <td class="num">{{ s.attempts }}</td>
                <td class="max-w-md truncate" [title]="s.lastError">{{ s.lastError }}</td>
                <td>{{ s.attempts >= 10 ? 'never (gave up)' : (s.nextAttemptAt | ist) }}</td>
              </tr>
            }
          </tbody>
        </table>
      }
    }
  `,
})
export default class IngestHealthPage {
  readonly health = httpResource<IngestHealth>(() => '/api/ingest/health');

  readonly totals = computed(() => {
    const topics = this.health.hasValue() ? this.health.value().topics : [];
    return topics.reduce(
      (a, t) => ({ total: a.total + t.total, pending: a.pending + t.pending, failed: a.failed + t.failed, dead: a.dead + t.dead }),
      { total: 0, pending: 0, failed: 0, dead: 0 },
    );
  });
}
