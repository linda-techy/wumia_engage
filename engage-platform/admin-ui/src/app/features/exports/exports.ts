import { httpResource } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { TokenService } from '../../core/auth/token.service';
import { Api } from '../../shared/api';
import { IstPipe, problemDetail } from '../../shared/format';
import { EmptyState, ErrorState, LoadingState } from '../../shared/states';
import { Campaign } from '../campaigns/campaign-rules';
import { SegmentRow } from '../segments/segments';

interface ExportRow {
  id: string;
  kind: 'campaign_report' | 'segment_ids' | 'sends';
  params: Record<string, string>;
  includesPii: boolean;
  rowCount: number | null;
  status: string;
  createdAt: string;
  expiresAt: string;
  requestedBy: string;
}

const TODAY = new Date(Date.now() + 330 * 60 * 1000).toISOString().slice(0, 10);

/**
 * Exports: 3 per operator a day, kept 24 hours, downloaded through a
 * 15-minute link. Contact details only in a segment list, only for an OWNER,
 * with a reason.
 */
@Component({
  selector: 'app-exports',
  imports: [IstPipe, LoadingState, ErrorState, EmptyState],
  template: `
    <header class="mb-6">
      <h1 class="text-2xl font-semibold">Exports</h1>
      <p class="text-sm text-slate-500">Three a day. Files are deleted after 24 hours. Every export and download is audited.</p>
    </header>
    @if (error()) { <p class="mb-4 rounded bg-red-50 p-3 text-sm text-red-800" role="alert">{{ error() }}</p> }

    <section class="card mb-8 max-w-3xl">
      <form class="grid grid-cols-2 gap-4" (submit)="create($event)">
        <label class="text-sm"><span class="mb-1 block font-medium">What</span>
          <select class="input" [value]="kind()" (change)="kind.set(val($event))">
            <option value="sends">Sends, by date (no contact details)</option>
            <option value="campaign_report">Campaign report (totals)</option>
            <option value="segment_ids">Segment members</option>
          </select></label>
        @switch (kind()) {
          @case ('sends') {
            <span class="flex gap-2">
              <label class="text-sm"><span class="mb-1 block font-medium">From (IST)</span>
                <input type="date" class="input" [value]="from()" (input)="from.set(val($event))" /></label>
              <label class="text-sm"><span class="mb-1 block font-medium">To</span>
                <input type="date" class="input" [value]="to()" (input)="to.set(val($event))" /></label>
            </span>
          }
          @case ('campaign_report') {
            <label class="text-sm"><span class="mb-1 block font-medium">Campaign</span>
              <select class="input" [value]="campaignId()" (change)="campaignId.set(val($event))">
                <option value="" disabled>Pick a campaign</option>
                @for (c of campaigns.value() ?? []; track c.id) { <option [value]="c.id">{{ c.name }}</option> }
              </select></label>
          }
          @case ('segment_ids') {
            <label class="text-sm"><span class="mb-1 block font-medium">Segment</span>
              <select class="input" [value]="segmentId()" (change)="segmentId.set(val($event))">
                <option value="" disabled>Pick a segment</option>
                @for (s of segments.value() ?? []; track s.id) { <option [value]="s.id">{{ s.name }}</option> }
              </select></label>
            @if (isOwner()) {
              <label class="col-span-2 text-sm"><input type="checkbox" [checked]="pii()" (change)="pii.set(checked($event))" />
                Include phone and email (logged; counts toward the 50-a-day limit)</label>
              @if (pii()) {
                <label class="col-span-2 text-sm"><span class="mb-1 block font-medium">Why you need contact details</span>
                  <input class="input" [value]="reason()" (input)="reason.set(val($event))" /></label>
              }
            }
          }
        }
        <div class="col-span-2">
          <button type="submit" class="btn-primary" [disabled]="busy() || !ready()">Create export</button>
        </div>
      </form>
    </section>

    @if (exports.isLoading() && !exports.hasValue()) {
      <app-loading />
    } @else if (exports.error()) {
      <app-error [error]="exports.error()" (retry)="exports.reload()" />
    } @else if (exports.hasValue()) {
      @if (exports.value().length === 0) {
        <app-empty text="No exports yet." />
      } @else {
        <table class="table">
          <thead><tr><th>Created</th><th>What</th><th class="num">Rows</th><th>Status</th><th>By</th><th></th></tr></thead>
          <tbody>
            @for (e of exports.value(); track e.id) {
              <tr>
                <td>{{ e.createdAt | ist }}</td>
                <td>{{ e.kind }}{{ e.includesPii ? ' · with contact details' : '' }}</td>
                <td class="num">{{ e.rowCount ?? '—' }}</td>
                <td>{{ e.status }}<span class="text-xs text-slate-500">{{ e.status === 'ready' ? ' · until ' : '' }}{{ e.status === 'ready' ? (e.expiresAt | ist) : '' }}</span></td>
                <td>{{ e.requestedBy }}</td>
                <td>
                  @if (e.status === 'ready' && e.requestedBy === me()) {
                    <button type="button" class="btn py-1" [disabled]="busy()" (click)="download(e)">Download</button>
                  }
                </td>
              </tr>
            }
          </tbody>
        </table>
      }
    }
  `,
})
export default class ExportsPage {
  readonly #api = inject(Api);
  readonly #tokens = inject(TokenService);

  readonly exports = httpResource<ExportRow[]>(() => '/api/exports');
  readonly campaigns = httpResource<Campaign[]>(() => (this.kind() === 'campaign_report' ? '/api/campaigns' : undefined));
  readonly segments = httpResource<SegmentRow[]>(() => (this.kind() === 'segment_ids' ? '/api/segments' : undefined));
  readonly isOwner = computed(() => this.#tokens.has('OWNER'));
  readonly me = computed(() => this.#tokens.operator()?.email);

  readonly kind = signal('sends');
  readonly from = signal(TODAY);
  readonly to = signal(TODAY);
  readonly campaignId = signal('');
  readonly segmentId = signal('');
  readonly pii = signal(false);
  readonly reason = signal('');
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);

  readonly ready = computed(() => {
    switch (this.kind()) {
      case 'sends':
        return !!this.from() && !!this.to();
      case 'campaign_report':
        return !!this.campaignId();
      default:
        return !!this.segmentId() && (!this.pii() || this.reason().trim().length >= 5);
    }
  });

  val(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }

  checked(e: Event): boolean {
    return (e.target as HTMLInputElement).checked;
  }

  async create(event: Event): Promise<void> {
    event.preventDefault();
    const kind = this.kind();
    const params =
      kind === 'sends' ? { from: this.from(), to: this.to() } : kind === 'campaign_report' ? { campaignId: this.campaignId() } : { segmentId: this.segmentId() };
    this.busy.set(true);
    this.error.set(null);
    try {
      await this.#api.post('/api/exports', { kind, params, includePii: kind === 'segment_ids' && this.pii(), reason: this.reason().trim() || null });
      this.exports.reload();
    } catch (e) {
      this.error.set(problemDetail(e, 'Could not create the export.'));
    } finally {
      this.busy.set(false);
    }
  }

  /** A fresh 15-minute link, then a plain browser download: the link is the credential. */
  async download(e: ExportRow): Promise<void> {
    this.busy.set(true);
    this.error.set(null);
    try {
      const r = await this.#api.post<{ url: string }>(`/api/exports/${e.id}/link`, {});
      window.location.assign(r.url);
    } catch (err) {
      this.error.set(problemDetail(err, 'Could not get a download link.'));
    } finally {
      this.busy.set(false);
    }
  }
}
