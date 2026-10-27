import { HttpClient, httpResource, HttpParams } from '@angular/common/http';
import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { IstPipe, problemDetail } from '../../shared/format';
import { ErrorState, LoadingState } from '../../shared/states';
import { journeyLabel, LogPage, LogRow, messageText, outcomeText, outcomeTone } from './message-log-model';

/** "2026-10-28", the IST day n days before today. */
function istDay(daysAgo = 0): string {
  const d = new Date(Date.now() + 5.5 * 3_600_000 - daysAgo * 86_400_000);
  return d.toISOString().slice(0, 10);
}

/**
 * Every push, WhatsApp, SMS and email Engage sent or decided not to send,
 * newest first: who, what, and what happened. Click a customer for their page.
 */
@Component({
  selector: 'app-message-log',
  imports: [RouterLink, IstPipe, LoadingState, ErrorState],
  template: `
    <h1 class="mb-1 text-2xl font-semibold">Message log</h1>
    <p class="mb-4 text-sm text-slate-600">Every message to every customer, newest first. Phone numbers and emails are masked here; open the customer for the full record.</p>

    <form class="mb-3 flex flex-wrap items-end gap-2 text-sm" (submit)="$event.preventDefault()" aria-label="Filters">
      <label class="flex flex-col">Channel
        <select class="input" [value]="channel()" (change)="channel.set(val($event))">
          <option value="">All</option><option value="push">Push</option><option value="whatsapp">WhatsApp</option>
          <option value="sms">SMS</option><option value="email">Email</option>
        </select>
      </label>
      <label class="flex flex-col">Outcome
        <select class="input" [value]="outcome()" (change)="outcome.set(val($event))">
          <option value="">All</option><option value="sent">Sent</option><option value="delivered">Delivered</option>
          <option value="read">Read</option><option value="clicked">Tapped</option><option value="blocked">Not sent</option>
          <option value="deferred">Held</option><option value="failed">Failed</option>
        </select>
      </label>
      <label class="flex flex-col">Journey
        <select class="input" [value]="journey()" (change)="journey.set(val($event))">
          <option value="">All</option>
          @for (j of journeys.value() ?? []; track j) { <option [value]="j">{{ j === 'campaign' ? 'Campaigns' : label(j) }}</option> }
        </select>
      </label>
      <label class="flex flex-col">From <input class="input" type="date" [value]="from()" (change)="from.set(val($event))" /></label>
      <label class="flex flex-col">To <input class="input" type="date" [value]="to()" (change)="to.set(val($event))" /></label>
      <label class="flex min-w-56 flex-1 flex-col">Customer
        <input class="input" type="search" placeholder="Exact email or phone" [value]="qDraft()" (input)="qDraft.set(val($event))"
               (keydown.enter)="q.set(qDraft().trim())" (search)="q.set(qDraft().trim())" />
      </label>
      <button type="button" class="btn" (click)="q.set(qDraft().trim())">Search</button>
      @if (filtered()) { <button type="button" class="btn" (click)="clear()">Clear</button> }
    </form>

    @if (page.value(); as p) {
      @if (!p.customerNotFound) {
        <div class="mb-3 flex flex-wrap gap-2 text-xs" aria-label="Totals for these filters">
          @for (c of summary(); track c[0]) {
            <span class="rounded-full border border-slate-200 bg-white px-3 py-1"><span class="font-semibold">{{ c[1] }}</span> {{ c[0] }}</span>
          }
        </div>
      }
    }

    @if (page.isLoading() && !rows().length) {
      <app-loading />
    } @else if (page.error()) {
      <app-error [error]="page.error()" (retry)="page.reload()" />
    } @else if (page.value()?.customerNotFound) {
      <p class="rounded border border-amber-300 bg-amber-50 p-3 text-sm">No customer with exactly that email or phone.</p>
    } @else if (!rows().length) {
      <p class="rounded border border-slate-200 bg-white p-4 text-sm text-slate-500">No messages for these filters.</p>
    } @else {
      <div class="overflow-x-auto rounded border border-slate-200 bg-white">
        <table class="table min-w-[56rem]">
          <thead><tr><th>When</th><th>Customer</th><th>Channel</th><th>Message</th><th>Outcome</th></tr></thead>
          <tbody>
            @for (r of rows(); track r.id) {
              <tr class="align-top">
                <td class="whitespace-nowrap">{{ r.createdAt | ist }}</td>
                <td>
                  <a [routerLink]="['/customers', r.identityId]" class="font-medium underline">{{ r.name || 'Customer' }}</a>
                  <div class="text-xs text-slate-500">{{ r.phone || r.email || '' }}</div>
                </td>
                <td><span class="rounded px-2 py-0.5 text-xs font-medium" [class]="channelClass(r.channel)">{{ channelName(r.channel) }}</span></td>
                <td class="max-w-md">
                  <div class="text-xs text-slate-500">{{ label(r.intentKey, r.campaign) }} · {{ r.templateKey }}</div>
                  @if (text(r); as t) {
                    <p class="whitespace-pre-wrap break-words text-sm" [class.line-clamp-3]="!open().has(r.id)">{{ t }}</p>
                    @if (t.length > 160) {
                      <button type="button" class="text-xs underline" (click)="toggle(r.id)">{{ open().has(r.id) ? 'Less' : 'More' }}</button>
                    }
                  }
                </td>
                <td class="whitespace-nowrap">
                  <span class="font-medium" [class.text-emerald-700]="tone(r) === 'good'" [class.text-amber-700]="tone(r) === 'wait'"
                        [class.text-red-700]="tone(r) === 'bad'">{{ outcomeOf(r) }}</span>
                  @if (r.clickedAt) { <div class="text-xs text-slate-500">tapped {{ r.clickedAt | ist }}</div> }
                  @else if (r.readAt) { <div class="text-xs text-slate-500">read {{ r.readAt | ist }}</div> }
                  @else if (r.deliveredAt) { <div class="text-xs text-slate-500">delivered {{ r.deliveredAt | ist }}</div> }
                </td>
              </tr>
            }
          </tbody>
        </table>
      </div>
      @if (more()) {
        <button type="button" class="btn mt-3" [disabled]="loadingMore()" (click)="loadMore()">{{ loadingMore() ? 'Loading…' : 'Load more' }}</button>
      }
      @if (moreError()) { <p class="mt-2 text-sm text-red-700" role="alert">{{ moreError() }}</p> }
    }
  `,
})
export default class MessageLogPage {
  readonly #http = inject(HttpClient);

  readonly channel = signal('');
  readonly outcome = signal('');
  readonly journey = signal('');
  readonly from = signal(istDay(6));
  readonly to = signal(istDay(0));
  readonly q = signal('');
  readonly qDraft = signal('');
  readonly open = signal(new Set<number>());

  readonly journeys = httpResource<string[]>(() => '/api/message-log/journeys');
  readonly page = httpResource<LogPage>(() => ({ url: '/api/message-log', params: this.#params() }));

  /** Rows from "Load more", appended after the first page. */
  readonly extra = signal<LogRow[]>([]);
  readonly extraMore = signal<boolean | null>(null);
  readonly loadingMore = signal(false);
  readonly moreError = signal<string | null>(null);

  readonly rows = computed(() => [...(this.page.value()?.rows ?? []), ...this.extra()]);
  readonly more = computed(() => this.extraMore() ?? this.page.value()?.more ?? false);
  readonly filtered = computed(() => !!(this.channel() || this.outcome() || this.journey() || this.q()));
  readonly summary = computed(() => {
    const c = this.page.value()?.counts ?? {};
    const n = (...keys: string[]) => keys.reduce((s, k) => s + (Number(c[k]) || 0), 0);
    return ([
      ['sent', n('sent', 'delivered', 'read', 'clicked')],
      ['delivered', n('delivered', 'read', 'clicked')],
      ['read', n('read')],
      ['tapped', n('clicked')],
      ['held', n('deferred')],
      ['not sent', n('blocked')],
      ['failed', n('failed')],
    ] as [string, number][]).filter(([, v]) => v > 0);
  });

  constructor() {
    // A new filter starts from the first page again.
    effect(() => {
      this.#params();
      untracked(() => {
        this.extra.set([]);
        this.extraMore.set(null);
        this.moreError.set(null);
      });
    });
  }

  #params(): Record<string, string> {
    const p: Record<string, string> = { from: this.from(), to: this.to() };
    if (this.channel()) p['channel'] = this.channel();
    if (this.outcome()) p['outcome'] = this.outcome();
    if (this.journey()) p['journey'] = this.journey();
    if (this.q()) p['q'] = this.q();
    return p;
  }

  async loadMore(): Promise<void> {
    const last = this.rows().at(-1);
    if (!last) return;
    this.loadingMore.set(true);
    this.moreError.set(null);
    try {
      const params = new HttpParams({ fromObject: { ...this.#params(), before: String(last.id) } });
      const next = await firstValueFrom(this.#http.get<LogPage>('/api/message-log', { params }));
      this.extra.update((rows) => [...rows, ...next.rows]);
      this.extraMore.set(!!next.more);
    } catch (e) {
      this.moreError.set(problemDetail(e, 'Could not load more.'));
    } finally {
      this.loadingMore.set(false);
    }
  }

  clear(): void {
    this.channel.set('');
    this.outcome.set('');
    this.journey.set('');
    this.q.set('');
    this.qDraft.set('');
  }

  toggle(id: number): void {
    this.open.update((s) => {
      const n = new Set(s);
      if (n.has(id)) n.delete(id);
      else n.add(id);
      return n;
    });
  }

  val(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }

  label(intentKey: string | null, campaign: string | null = null): string {
    return journeyLabel({ intentKey, campaign });
  }

  text(r: LogRow): string {
    return messageText(r);
  }

  outcomeOf(r: LogRow): string {
    return outcomeText(r);
  }

  tone(r: LogRow): string {
    return outcomeTone(r.status);
  }

  channelName(c: string): string {
    return c === 'whatsapp' ? 'WhatsApp' : c === 'sms' ? 'SMS' : c.charAt(0).toUpperCase() + c.slice(1);
  }

  channelClass(c: string): string {
    return c === 'whatsapp' ? 'bg-emerald-100 text-emerald-800' : c === 'push' ? 'bg-sky-100 text-sky-800'
      : c === 'sms' ? 'bg-violet-100 text-violet-800' : 'bg-slate-100 text-slate-700';
  }
}
