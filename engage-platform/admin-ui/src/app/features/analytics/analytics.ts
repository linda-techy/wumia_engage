import { NgTemplateOutlet } from '@angular/common';
import { httpResource } from '@angular/common/http';
import { Component, computed, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AnalyticsOverview, BlockDay, CampaignResultRow, SpendDay, WaQuality } from '../../core/api/models';
import { InrPipe, IstPipe, rupees } from '../../shared/format';
import { EmptyState, ErrorState, LoadingState } from '../../shared/states';
import { journeyLabel } from '../reports/reports';
import { blockTotals, dailyBars, lastDays, Metric, rate, reasonLabel, returnPerRupee, spendTotals } from './analytics-model';
import { BarChart } from './bar-chart';

type Tab = 'overview' | 'campaigns' | 'spend' | 'quality';

/**
 * How messages perform: what was sent and delivered, what people clicked,
 * the orders and revenue credited to messages (latest click within the click
 * window, else a delivered or read message within the view window), and what
 * it cost. For what a journey truly adds, against customers held out of it,
 * see Reports.
 */
@Component({
  selector: 'app-analytics',
  imports: [RouterLink, NgTemplateOutlet, InrPipe, IstPipe, LoadingState, ErrorState, EmptyState, BarChart],
  template: `
    <header class="mb-4 flex flex-wrap items-end justify-between gap-3">
      <div>
        <h1 class="text-2xl font-semibold">Analytics</h1>
        <p class="text-sm text-slate-500">Messages, clicks, orders and revenue, by channel, journey and campaign.</p>
      </div>
      <div class="flex flex-wrap items-end gap-2 text-sm">
        @for (d of presets; track d) {
          <button type="button" class="btn py-1" [class.btn-primary]="preset() === d" (click)="usePreset(d)">{{ d }} days</button>
        }
        <label class="flex flex-col">From<input type="date" class="input py-1" [value]="from()" (change)="setFrom($event)" /></label>
        <label class="flex flex-col">To<input type="date" class="input py-1" [value]="to()" (change)="setTo($event)" /></label>
      </div>
    </header>

    <div class="mb-5 flex flex-wrap gap-2" role="tablist" aria-label="Report">
      @for (t of tabs; track t.id) {
        <button type="button" role="tab" class="btn" [class.btn-primary]="tab() === t.id" [attr.aria-selected]="tab() === t.id"
                (click)="tab.set(t.id)">{{ t.label }}</button>
      }
    </div>

    @switch (tab()) {
      @case ('overview') {
        @if (overview.isLoading() && !overview.hasValue()) { <app-loading /> }
        @else if (overview.error()) { <app-error [error]="overview.error()" (retry)="overview.reload()" /> }
        @else if (overview.hasValue()) {
          @let o = overview.value();
          @let t = o.total;
          <section class="mb-6 grid gap-3 sm:grid-cols-3 lg:grid-cols-6">
            <div class="card"><p class="label">Sent</p><p class="stat">{{ t.sent }}</p></div>
            <div class="card"><p class="label">Delivered</p><p class="stat">{{ rate(t.delivered, t.sent) }}</p><p class="text-xs text-slate-500">{{ t.delivered }}</p></div>
            @let push = pushRow(o);
            <div class="card"><p class="label">Clicked (push)</p><p class="stat">{{ push ? rate(push.clicked, push.delivered) : '—' }}</p>
              <p class="text-xs text-slate-500">{{ push?.clicked ?? 0 }} of delivered; WhatsApp taps are not tracked</p></div>
            <div class="card"><p class="label">Orders</p><p class="stat">{{ t.orders }}</p><p class="text-xs text-slate-500">{{ t.clickOrders }} after a click</p></div>
            <div class="card"><p class="label">Revenue</p><p class="stat">{{ t.revenuePaise | inr }}</p></div>
            <div class="card"><p class="label">Cost</p><p class="stat">{{ t.costPaise | inr }}</p><p class="text-xs text-slate-500">{{ ret(t) }} back per ₹1</p></div>
          </section>

          <section class="card mb-6">
            <div class="mb-2 flex flex-wrap items-center justify-between gap-2">
              <h2 class="font-semibold">Day by day</h2>
              <div class="flex gap-1 text-sm">
                @for (m of metrics; track m.id) {
                  <button type="button" class="btn py-0.5" [class.btn-primary]="metric() === m.id" (click)="metric.set(m.id)">{{ m.label }}</button>
                }
              </div>
            </div>
            <app-bar-chart [bars]="bars()" [label]="metricLabel() + ' per day'" [format]="metric() === 'revenuePaise' ? money : count" />
          </section>

          <h2 class="mb-2 font-semibold">By channel</h2>
          <ng-container *ngTemplateOutlet="table; context: { rows: o.channels, name: channelName }" />
          <h2 class="mb-2 mt-6 font-semibold">By journey</h2>
          <ng-container *ngTemplateOutlet="table; context: { rows: o.journeys, name: journeyName }" />
          <p class="mt-3 text-xs text-slate-500">
            An order counts for a message if the customer clicked it in the {{ o.clickDays }} days before ordering, or else
            received or read it in the {{ o.viewHours }} hours before. Order updates never take the credit. What a journey
            adds over customers held out of it is in Reports.
          </p>
        }
      }

      @case ('campaigns') {
        @if (campaigns.isLoading() && !campaigns.hasValue()) { <app-loading /> }
        @else if (campaigns.error()) { <app-error [error]="campaigns.error()" (retry)="campaigns.reload()" /> }
        @else if (campaigns.hasValue()) {
          @if (campaigns.value().length === 0) {
            <app-empty text="No campaign started in these dates." />
          } @else {
            <div class="overflow-x-auto"><table class="table">
              <thead><tr><th>Campaign</th><th>Started</th><th class="num">People</th><th class="num">Sent</th><th class="num">Delivered</th>
                <th class="num">Clicked</th><th class="num">Orders</th><th class="num">Revenue</th><th class="num">Cost</th></tr></thead>
              <tbody>
                @for (c of campaigns.value(); track c.id) {
                  <tr>
                    <td><a [routerLink]="['/campaigns', c.id]" class="underline">{{ c.name }}</a>
                      <span class="text-xs text-slate-500"> · {{ c.channel }} · {{ c.status.toLowerCase() }}</span></td>
                    <td>{{ c.startedAt | ist }}</td>
                    <td class="num">{{ c.recipients }}</td>
                    <td class="num">{{ c.funnel.sent }}</td>
                    <td class="num">{{ rate(c.funnel.delivered, c.funnel.sent) }}</td>
                    <td class="num">{{ rate(c.funnel.clicked, c.funnel.delivered) }}</td>
                    <td class="num">{{ c.funnel.orders }}</td>
                    <td class="num">{{ c.funnel.revenuePaise | inr }}</td>
                    <td class="num">{{ c.funnel.costPaise | inr }}</td>
                  </tr>
                }
              </tbody>
            </table></div>
          }
        }
      }

      @case ('spend') {
        <div class="grid gap-6 lg:grid-cols-2">
          <section>
            <h2 class="mb-2 font-semibold">Spend</h2>
            @if (spend.hasValue()) {
              @if (spendRows().length === 0) { <app-empty text="Nothing spent in these dates." /> }
              @else {
                <app-bar-chart [bars]="spendBars()" label="Spend per day" [format]="money" />
                <table class="table mt-3">
                  <thead><tr><th>Channel</th><th>Kind</th><th class="num">Messages</th><th class="num">Spent</th></tr></thead>
                  <tbody>@for (r of spendRows(); track r.channel + r.category) {
                    <tr><td>{{ r.channel }}</td><td>{{ r.category }}</td><td class="num">{{ r.messages }}</td><td class="num">{{ r.paise | inr }}</td></tr>
                  }</tbody>
                </table>
              }
            } @else if (spend.error()) { <app-error [error]="spend.error()" (retry)="spend.reload()" /> } @else { <app-loading /> }
          </section>
          <section>
            <h2 class="mb-2 font-semibold">Why messages were not sent</h2>
            @if (blocks.hasValue()) {
              @if (blockRows().length === 0) { <app-empty text="Nothing held back in these dates." /> }
              @else {
                <table class="table">
                  <thead><tr><th>Reason</th><th>Outcome</th><th class="num">Messages</th></tr></thead>
                  <tbody>@for (r of blockRows(); track r.status + r.reason) {
                    <tr><td>{{ reason(r.reason) }}</td><td>{{ r.status === 'deferred' ? 'sent later' : 'not sent' }}</td><td class="num">{{ r.sends }}</td></tr>
                  }</tbody>
                </table>
              }
            } @else if (blocks.error()) { <app-error [error]="blocks.error()" (retry)="blocks.reload()" /> } @else { <app-loading /> }
          </section>
        </div>
      }

      @case ('quality') {
        @if (quality.hasValue()) {
          @let q = quality.value();
          <section class="mb-6">
            <h2 class="mb-2 font-semibold">WhatsApp templates</h2>
            @if (q.templates.length === 0) { <app-empty text="No WhatsApp templates synced yet." /> }
            @else {
              <table class="table">
                <thead><tr><th>Template</th><th>Status</th><th>Quality</th><th>Category</th><th>Checked</th></tr></thead>
                <tbody>@for (t of q.templates; track t.key + t.language) {
                  <tr>
                    <td class="font-mono text-xs">{{ t.key }} ({{ t.language }})</td>
                    <td>{{ t.status.toLowerCase() }}</td>
                    <td><span class="badge" [class.badge-green]="t.quality === 'GREEN'" [class.badge-red]="t.quality === 'RED'">{{ t.quality.toLowerCase() }}</span></td>
                    <td>{{ t.approvedCategory ?? t.requestedCategory }}@if (t.approvedCategory && t.approvedCategory !== t.requestedCategory) {
                      <span class="text-xs text-red-700"> (asked {{ t.requestedCategory }})</span> }</td>
                    <td>{{ t.syncedAt | ist }}</td>
                  </tr>
                }</tbody>
              </table>
            }
          </section>
          <section>
            <h2 class="mb-2 font-semibold">WhatsApp number quality, these dates</h2>
            @if (q.numbers.length === 0) { <app-empty text="No change recorded in these dates." /> }
            @else {
              <table class="table">
                <thead><tr><th>When</th><th>Number</th><th>Quality</th><th>Tier</th><th>Event</th></tr></thead>
                <tbody>@for (n of q.numbers; track n.phoneNumberId + n.observedAt + n.event) {
                  <tr><td>{{ n.observedAt | ist }}</td><td class="font-mono text-xs">{{ n.phoneNumberId }}</td><td>{{ n.quality.toLowerCase() }}</td>
                    <td>{{ n.messagingTier ?? '—' }}</td><td>{{ n.event.toLowerCase() }}</td></tr>
                }</tbody>
              </table>
            }
          </section>
        } @else if (quality.error()) { <app-error [error]="quality.error()" (retry)="quality.reload()" /> } @else { <app-loading /> }
      }
    }

    <ng-template #table let-rows="rows" let-name="name">
      <div class="overflow-x-auto"><table class="table">
        <thead><tr><th></th><th class="num">Sent</th><th class="num">Delivered</th><th class="num">Read</th><th class="num">Clicked</th>
          <th class="num">Not sent</th><th class="num">Orders</th><th class="num">Revenue</th><th class="num">Cost</th><th class="num">Back per ₹1</th></tr></thead>
        <tbody>
          @for (f of rows; track f.key) {
            <tr>
              <td>{{ name(f.key) }}</td>
              <td class="num">{{ f.sent }}</td>
              <td class="num">{{ rate(f.delivered, f.sent) }}</td>
              <td class="num">{{ f.read ? rate(f.read, f.delivered) : '—' }}</td>
              <td class="num">{{ clicks(f) }}</td>
              <td class="num">{{ f.blocked + f.failed }}</td>
              <td class="num">{{ f.orders }}</td>
              <td class="num">{{ f.revenuePaise | inr }}</td>
              <td class="num">{{ f.costPaise | inr }}</td>
              <td class="num">{{ ret(f) }}</td>
            </tr>
          } @empty {
            <tr><td colspan="10" class="text-slate-500">No messages in these dates.</td></tr>
          }
        </tbody>
      </table></div>
    </ng-template>
  `,
})
export default class AnalyticsPage {
  readonly presets = [7, 30, 90];
  readonly tabs: { id: Tab; label: string }[] = [
    { id: 'overview', label: 'Overview' },
    { id: 'campaigns', label: 'Campaigns' },
    { id: 'spend', label: 'Spend and blocks' },
    { id: 'quality', label: 'WhatsApp quality' },
  ];
  readonly metrics: { id: Metric; label: string }[] = [
    { id: 'sent', label: 'Sent' },
    { id: 'clicked', label: 'Clicked' },
    { id: 'orders', label: 'Orders' },
    { id: 'revenuePaise', label: 'Revenue' },
  ];

  readonly tab = signal<Tab>('overview');
  readonly preset = signal<number | null>(30);
  readonly from = signal(lastDays(30).from);
  readonly to = signal(lastDays(30).to);
  readonly metric = signal<Metric>('sent');

  readonly #q = computed(() => `from=${this.from()}&to=${this.to()}`);
  readonly overview = httpResource<AnalyticsOverview>(() => (this.tab() === 'overview' ? `/api/analytics/overview?${this.#q()}` : undefined));
  readonly campaigns = httpResource<CampaignResultRow[]>(() => (this.tab() === 'campaigns' ? `/api/analytics/campaigns?${this.#q()}` : undefined));
  readonly spend = httpResource<SpendDay[]>(() => (this.tab() === 'spend' ? `/api/reports/spend?${this.#q()}` : undefined));
  readonly blocks = httpResource<BlockDay[]>(() => (this.tab() === 'spend' ? `/api/reports/blocks?${this.#q()}` : undefined));
  readonly quality = httpResource<WaQuality>(() => (this.tab() === 'quality' ? `/api/reports/quality?${this.#q()}` : undefined));

  readonly bars = computed(() => dailyBars(this.overview.value()?.daily ?? [], this.metric(), this.from(), this.to()));
  readonly metricLabel = computed(() => this.metrics.find((m) => m.id === this.metric())?.label ?? '');
  readonly spendRows = computed(() => spendTotals(this.spend.value() ?? []));
  readonly spendBars = computed(() => {
    const byDay = (this.spend.value() ?? []).map((d) => ({ day: d.day, channel: d.channel, sent: 0, delivered: 0, clicked: 0, orders: 0, revenuePaise: d.paise }));
    return dailyBars(byDay, 'revenuePaise', this.from(), this.to());
  });
  readonly blockRows = computed(() => blockTotals(this.blocks.value() ?? []));

  rate = rate;
  ret = returnPerRupee;
  reason = reasonLabel;
  money = (v: number) => rupees(v);
  count = (v: number) => String(v);
  channelName = (k: string) => ({ push: 'Push', whatsapp: 'WhatsApp', sms: 'SMS', email: 'Email' })[k] ?? k;
  journeyName = (k: string) => (k === 'campaigns' ? 'Campaigns (all)' : journeyLabel(k));

  /** Only push clicks are tracked: WhatsApp and SMS say so, and a journey with reads but no clicks shows "—", not 0%. */
  clicks(f: AnalyticsOverview['channels'][number]): string {
    if (f.key === 'whatsapp' || f.key === 'sms') return 'not tracked';
    if (f.clicked === 0 && f.read > 0) return '—';
    return rate(f.clicked, f.delivered);
  }

  /** The push row: the only channel whose clicks are tracked. */
  pushRow(o: AnalyticsOverview): AnalyticsOverview['channels'][number] | undefined {
    return o.channels.find((c) => c.key === 'push');
  }

  usePreset(days: number): void {
    const r = lastDays(days);
    this.preset.set(days);
    this.from.set(r.from);
    this.to.set(r.to);
  }

  setFrom(e: Event): void {
    const v = (e.target as HTMLInputElement).value;
    if (v) { this.from.set(v); this.preset.set(null); }
  }

  setTo(e: Event): void {
    const v = (e.target as HTMLInputElement).value;
    if (v) { this.to.set(v); this.preset.set(null); }
  }
}
