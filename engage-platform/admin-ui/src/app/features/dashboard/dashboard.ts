import { httpResource } from '@angular/common/http';
import { Component, computed, DestroyRef, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { InrPipe, IstPipe } from '../../shared/format';
import { EmptyState, ErrorState, LoadingState } from '../../shared/states';
import {
  attention, blockReasons, budgetPct, change, CHANNEL_NAMES, Dashboard, journeyName, journeyRows, logChannel, optInsByDay,
  Period, periodLabel, previousLabel, qualityText, rate, roi, sendTotals, since, tierLimit, tierText,
} from './dashboard-model';

/**
 * The dashboard (owner, 2026-10-30), polled every 30 s: what messages earned
 * and what needs someone first, then how each channel performs and grows, then
 * the operations health (spend, block reasons, journeys). The kill switches are
 * in the header on every page.
 */
@Component({
  selector: 'app-dashboard',
  imports: [InrPipe, IstPipe, LoadingState, ErrorState, EmptyState, RouterLink],
  template: `
    <header class="mb-6 flex flex-wrap items-end justify-between gap-3">
      <div>
        <h1 class="text-2xl font-semibold">Dashboard</h1>
        <p class="text-sm text-slate-500">Refreshes every 30 seconds.@if (data()) { Last: {{ data()!.generatedAt | ist }}. }</p>
      </div>
      <div class="flex items-center gap-2">
        <div class="inline-flex rounded border border-slate-300 bg-white p-0.5 text-sm" role="group" aria-label="Period">
          @for (p of periods; track p) {
            <button type="button" class="rounded px-3 py-1.5" [class.bg-slate-900]="days() === p" [class.text-white]="days() === p"
                    [attr.aria-pressed]="days() === p" (click)="days.set(p)">{{ p === 1 ? 'Today' : p + ' days' }}</button>
          }
        </div>
        <button type="button" class="btn" (click)="dash.reload()">Refresh</button>
      </div>
    </header>

    @if (dash.isLoading() && !dash.hasValue()) {
      <app-loading />
    } @else if (dash.error() && !dash.hasValue()) {
      <app-error [error]="dash.error()" (retry)="dash.reload()" />
    } @else if (data(); as d) {
      <!-- Needs attention -->
      @if (alerts().length) {
        <section class="mb-6 space-y-2" aria-label="Needs attention">
          @for (a of alerts(); track a.text) {
            <div class="flex items-start justify-between gap-3 rounded-lg border p-3 text-sm"
                 [class.border-red-200]="a.tone === 'bad'" [class.bg-red-50]="a.tone === 'bad'" [class.text-red-900]="a.tone === 'bad'"
                 [class.border-amber-200]="a.tone === 'warn'" [class.bg-amber-50]="a.tone === 'warn'" [class.text-amber-900]="a.tone === 'warn'"
                 [class.border-slate-200]="a.tone === 'info'" [class.bg-slate-50]="a.tone === 'info'" [class.text-slate-700]="a.tone === 'info'"
                 [attr.role]="a.tone === 'bad' ? 'alert' : null">
              <span>{{ a.text }}</span>
              @if (a.link) { <a class="shrink-0 font-medium underline" [routerLink]="a.link" [queryParams]="a.query ?? {}">Open</a> }
            </div>
          }
        </section>
      }

      <!-- 1. What messages earned, who can be reached, chats waiting -->
      <section class="mb-8">
        <h2 class="mb-3 font-semibold">{{ period() }}</h2>
        <div class="grid grid-cols-2 gap-3 lg:grid-cols-5">
          <div class="card col-span-2 lg:col-span-1">
            <p class="label">Revenue from messages</p>
            <p class="stat">{{ d.headline.revenuePaise | inr }}</p>
            <p class="mt-1 text-xs text-slate-500">
              {{ d.headline.orders }} order{{ d.headline.orders === 1 ? '' : 's' }}
              @if (revenueChange() !== null) {
                · <span [class.text-emerald-700]="revenueChange()! > 0" [class.text-red-700]="revenueChange()! < 0">{{ revenueChange()! > 0 ? '+' : '' }}{{ revenueChange() }}%</span> on {{ previous() }}
              }
            </p>
          </div>
          <div class="card">
            <p class="label">Return on spend</p>
            <p class="stat">{{ roiValue() === null ? '—' : roiValue()!.toFixed(1) + '×' }}</p>
            <p class="mt-1 text-xs text-slate-500">{{ d.headline.spendPaise | inr }} spent on WhatsApp</p>
          </div>
          <div class="card">
            <p class="label">Reachable on WhatsApp</p>
            <p class="stat">{{ d.audience.whatsapp }}</p>
            <p class="mt-1 text-xs text-slate-500">opted in to offers · +{{ d.audience.whatsappNew }} new</p>
          </div>
          <div class="card">
            <p class="label">Push subscribers</p>
            <p class="stat">{{ d.audience.push }}</p>
            <p class="mt-1 text-xs text-slate-500">people with notifications on · +{{ d.audience.pushNew }} new</p>
          </div>
          <a class="card block hover:border-slate-400" routerLink="/inbox">
            <p class="label">Chats waiting</p>
            <p class="stat" [class.text-amber-700]="d.inbox.waiting > 0">{{ d.inbox.waiting }}</p>
            <p class="mt-1 text-xs text-slate-500">
              @if (d.inbox.waiting > 0) { oldest {{ waitingFor() }} } @else { all answered }
            </p>
          </a>
        </div>
        <p class="mt-2 text-xs text-slate-500">
          Revenue is credited to the last message clicked (7 days) or delivered (24 hours) before an order, cancelled and refunded amounts left out.
          <a class="underline" routerLink="/analytics">Analytics</a> has it by channel, journey and day.
        </p>
      </section>

      <!-- 2. Recovery -->
      <section class="mb-8">
        <h2 class="mb-3 font-semibold">Abandoned cart and checkout recovery</h2>
        <div class="grid gap-3 md:grid-cols-2">
          @for (r of d.recovery; track r.journey) {
            <div class="card">
              <div class="flex items-baseline justify-between">
                <p class="font-medium">{{ journeyName(r.journey) }}</p>
                <a class="text-xs underline" routerLink="/messages" [queryParams]="{ journey: r.journey }">Messages</a>
              </div>
              <div class="mt-2 grid grid-cols-3 gap-2 text-center">
                <div><p class="label">Reminded</p><p class="stat">{{ r.started }}</p></div>
                <div><p class="label">Recovered</p><p class="stat">{{ r.recovered }}</p><p class="text-xs text-slate-500">{{ rate(r.recovered, r.started) }}</p></div>
                <div><p class="label">Revenue</p><p class="stat text-xl">{{ r.revenuePaise | inr }}</p></div>
              </div>
            </div>
          }
        </div>
      </section>

      <!-- 3. Channels -->
      <section class="mb-8">
        <h2 class="mb-1 font-semibold">How each channel performs</h2>
        <p class="mb-3 text-xs text-slate-500">{{ period() }}. Rates are of messages sent. Click a row for the messages.</p>
        @if (d.channels.length === 0) {
          <app-empty text="No messages in this period." />
        } @else {
          <div class="overflow-x-auto">
            <table class="table min-w-[36rem]">
              <thead><tr><th>Channel</th><th class="num">Sent</th><th class="num">Delivered</th><th class="num">Read</th><th class="num">Tapped</th><th class="num">Failed</th><th class="num">Not sent</th></tr></thead>
              <tbody>
                @for (c of d.channels; track c.channel) {
                  <tr class="cursor-pointer hover:bg-slate-50" [routerLink]="'/messages'" [queryParams]="{ channel: logChannel(c.channel) }">
                    <td class="font-medium">{{ channelName(c.channel) }}</td>
                    <td class="num">{{ c.sent }}</td>
                    <td class="num">{{ rate(c.delivered, c.sent) }}</td>
                    <td class="num">{{ c.channel === 'push' ? '—' : rate(c.read, c.sent) }}</td>
                    <td class="num">{{ rate(c.clicked, c.sent) }}</td>
                    <td class="num" [class.text-red-700]="c.failed > 0">{{ c.failed }}</td>
                    <td class="num">{{ c.blocked }}</td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        }
      </section>

      <!-- 4. WhatsApp health and sign-ups -->
      <section class="mb-8 grid gap-6 lg:grid-cols-2">
        <div>
          <h2 class="mb-3 font-semibold">WhatsApp number</h2>
          @if (d.waNumber[0]; as n) {
            <div class="card">
              <p class="font-medium">{{ n.displayNumber }} @if (n.verifiedName) { <span class="text-slate-500">· {{ n.verifiedName }}</span> }</p>
              <dl class="mt-3 grid grid-cols-2 gap-3 text-sm">
                <div><dt class="label">Quality</dt>
                  <dd class="mt-1 font-semibold" [class.text-emerald-700]="n.quality === 'GREEN'" [class.text-amber-700]="n.quality === 'YELLOW'" [class.text-red-700]="n.quality === 'RED'">{{ qualityText(n.quality) }}</dd></div>
                <div><dt class="label">Limit</dt><dd class="mt-1 font-semibold">{{ tierText(n.messagingTier) }}</dd></div>
              </dl>
              @if (tierLimit(n.messagingTier); as limit) {
                @let p = usedPct(n.used24h, limit);
                <p class="mt-3 text-xs text-slate-600">{{ n.used24h }} of {{ limit }} people messaged first in the last 24 h (replies to customers are not counted)</p>
                <div class="mt-1 h-2 overflow-hidden rounded bg-slate-100" role="progressbar" [attr.aria-valuenow]="p" aria-valuemin="0" aria-valuemax="100">
                  <div class="h-full" [class.bg-emerald-500]="p < 80" [class.bg-amber-500]="p >= 80 && p < 100" [class.bg-red-600]="p >= 100" [style.width.%]="p"></div>
                </div>
              }
              <p class="mt-3 text-xs text-slate-500">
                Templates: {{ approved() }} approved@if (pendingTemplates()) {, {{ pendingTemplates() }} waiting or rejected}.
                @if (unratedTemplates()) { Meta rates template quality only after enough sends. }
              </p>
            </div>
          } @else {
            <app-empty text="The sending number has not synced from Meta yet (every 30 minutes)." />
          }
        </div>
        <div>
          <h2 class="mb-1 font-semibold">Sign-ups on the website</h2>
          <p class="mb-3 text-xs text-slate-500">{{ period() }}, in people (one browser = one).</p>
          <div class="grid gap-3 sm:grid-cols-2">
            <div class="card text-sm">
              <p class="font-medium">Push notifications card</p>
              <dl class="mt-2 space-y-1">
                <div class="flex justify-between"><dt>Saw the card</dt><dd class="tabular-nums">{{ d.pushFunnel.shown }}</dd></div>
                <div class="flex justify-between"><dt>Tapped Allow</dt><dd class="tabular-nums">{{ d.pushFunnel.accepted }}</dd></div>
                <div class="flex justify-between font-semibold"><dt>Turned on</dt><dd class="tabular-nums">{{ d.pushFunnel.allowed }}</dd></div>
                <div class="flex justify-between text-slate-600"><dt>Browser blocked</dt><dd class="tabular-nums">{{ d.pushFunnel.blocked + d.pushFunnel.closed }}</dd></div>
                <div class="flex justify-between text-slate-600"><dt>iPhone / in-app (no push)</dt><dd class="tabular-nums">{{ d.pushFunnel.cannotPush }}</dd></div>
              </dl>
              @if (d.pushFunnel.blocked > 0) {
                <p class="mt-2 text-xs text-slate-500">{{ d.pushFunnel.blockedBySetting }} of {{ d.pushFunnel.blocked }} blocks came from a phone or browser setting, not the shopper's tap. They are offered WhatsApp instead.</p>
              }
            </div>
            <div class="card text-sm">
              <p class="font-medium">WhatsApp offer card</p>
              <dl class="mt-2 space-y-1">
                <div class="flex justify-between"><dt>Saw the card</dt><dd class="tabular-nums">{{ d.waOfferFunnel.shown }}</dd></div>
                <div class="flex justify-between"><dt>Tapped</dt><dd class="tabular-nums">{{ d.waOfferFunnel.tapped }}</dd></div>
                <div class="flex justify-between font-semibold"><dt>Joined on WhatsApp</dt><dd class="tabular-nums">{{ d.waOfferFunnel.joined }}</dd></div>
              </dl>
              <p class="mt-2 text-xs text-slate-500">{{ rate(d.waOfferFunnel.joined, d.waOfferFunnel.shown) }} of those who saw it joined.</p>
            </div>
          </div>
        </div>
      </section>

      <!-- 5. Opt-ins -->
      <section class="mb-8">
        <h2 class="mb-1 font-semibold">Opt-ins and opt-outs per day</h2>
        <p class="mb-3 text-xs text-slate-500">People who agreed to offers, 14 days. Rising opt-outs come before a WhatsApp quality drop, usually by a few days.</p>
        @if (optIns().length === 0) {
          <app-empty text="No opt-ins or opt-outs in 14 days." />
        } @else {
          <div class="flex items-end gap-1 overflow-x-auto rounded-lg border border-slate-200 bg-white p-4" aria-label="Opt-ins and opt-outs per day">
            @for (o of optIns(); track o.day) {
              <div class="flex w-10 shrink-0 flex-col items-center gap-1 text-xs" [title]="o.day + ': ' + o.whatsapp + ' WhatsApp, ' + o.push + ' push, ' + o.out + ' opted out'">
                <span class="tabular-nums text-red-700">{{ o.out || '' }}</span>
                <div class="w-3 rounded-t bg-red-400" [style.height.px]="bar(o.out)"></div>
                <div class="w-3 bg-emerald-500" [style.height.px]="bar(o.whatsapp)"></div>
                <div class="w-3 rounded-b bg-sky-400" [style.height.px]="bar(o.push)"></div>
                <span class="tabular-nums text-slate-700">{{ (o.whatsapp + o.push) || '' }}</span>
                <span class="text-slate-500">{{ o.day.slice(5) }}</span>
              </div>
            }
          </div>
          <p class="mt-2 flex gap-4 text-xs text-slate-500">
            <span><span class="mr-1 inline-block h-2 w-2 rounded-sm bg-emerald-500"></span>WhatsApp</span>
            <span><span class="mr-1 inline-block h-2 w-2 rounded-sm bg-sky-400"></span>Push</span>
            <span><span class="mr-1 inline-block h-2 w-2 rounded-sm bg-red-400"></span>Opted out</span>
          </p>
        }
      </section>

      <!-- 6. Operations health -->
      <h2 class="mb-3 border-t border-slate-200 pt-6 text-lg font-semibold">Operations health</h2>
      <section class="mb-8 grid gap-6 lg:grid-cols-2">
        <div>
          <h3 class="mb-3 font-semibold">Last 24 hours</h3>
          <div class="grid grid-cols-2 gap-3 sm:grid-cols-4">
            <div class="card"><p class="label">Sent</p><p class="stat">{{ totals().sent }}</p></div>
            <div class="card"><p class="label">Not sent</p><p class="stat">{{ totals().blocked }}</p></div>
            <div class="card"><p class="label">Held</p><p class="stat">{{ totals().deferred }}</p></div>
            <div class="card"><p class="label">Failed</p><p class="stat" [class.text-red-700]="totals().failed > 0">{{ totals().failed }}</p></div>
          </div>
          <h3 class="mb-2 mt-6 font-semibold">Spend today</h3>
          @for (s of d.spendToday; track s.channel + s.category) {
            <div class="card mb-2">
              <div class="flex justify-between text-sm">
                <span class="font-medium">{{ channelName(s.channel) }} · {{ s.category }}</span>
                <span class="tabular-nums">{{ s.spentPaise | inr }} @if (s.budgetPaise) { of {{ s.budgetPaise | inr }} }</span>
              </div>
              @if (pct(s) !== null) {
                @let p = pct(s) ?? 0;
                <div class="mt-2 h-2 overflow-hidden rounded bg-slate-100" role="progressbar" [attr.aria-valuenow]="p" aria-valuemin="0" aria-valuemax="100">
                  <div class="h-full" [class.bg-emerald-500]="p < 80" [class.bg-amber-500]="p >= 80 && p < 100" [class.bg-red-600]="p >= 100" [style.width.%]="p"></div>
                </div>
              }
              <p class="mt-1 text-xs text-slate-500">{{ s.messages }} messages</p>
            </div>
          } @empty {
            <app-empty text="No paid channel has a budget or spend yet." />
          }
        </div>
        <div>
          <h3 class="mb-3 font-semibold">Why messages were not sent, 24 h</h3>
          @if (reasons().length === 0) {
            <app-empty text="Nothing blocked or held." />
          } @else {
            <div class="overflow-x-auto">
              <table class="table">
                <thead><tr><th>Reason</th><th>Outcome</th><th class="num">Messages</th></tr></thead>
                <tbody>
                  @for (r of reasons(); track r.status + r.reason) {
                    <tr class="cursor-pointer hover:bg-slate-50" [class.text-slate-500]="r.expected"
                        [routerLink]="'/messages'" [queryParams]="{ outcome: r.status }" [title]="r.reason">
                      <td>{{ r.label }}@if (r.expected) { <span class="ml-1 text-xs">(expected)</span> }</td>
                      <td>{{ r.status === 'deferred' ? 'Held, sent later' : 'Not sent' }}</td>
                      <td class="num">{{ r.sends }}</td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
          }
        </div>
      </section>

      <section class="mb-8">
        <h3 class="mb-1 font-semibold">Journeys, 24 h</h3>
        <p class="mb-3 text-xs text-slate-500">Goal met = the customer acted (ordered, read or tapped), so the journey stopped. All steps sent = it ran to the end. Stuck = a step more than 15 minutes late.</p>
        @if (journeys().length === 0) {
          <app-empty text="No journey activity in the last 24 hours." />
        } @else {
          <div class="overflow-x-auto">
            <table class="table min-w-[32rem]">
              <thead><tr><th>Journey</th><th class="num">Started</th><th class="num">Goal met</th><th class="num">All steps sent</th><th class="num">Failed</th><th class="num">Stuck</th></tr></thead>
              <tbody>
                @for (j of journeys(); track j.intentKey) {
                  <tr class="cursor-pointer hover:bg-slate-50" [routerLink]="'/messages'" [queryParams]="{ journey: j.intentKey }" [title]="j.intentKey">
                    <td>{{ journeyName(j.intentKey) }}</td>
                    <td class="num">{{ j.entered24h }}</td>
                    <td class="num">{{ j.succeeded24h }}</td>
                    <td class="num">{{ j.exhausted24h }}</td>
                    <td class="num" [class.text-red-700]="j.failed24h > 0">{{ j.failed24h }}</td>
                    <td class="num" [class.font-semibold]="j.stalled > 0" [class.text-red-700]="j.stalled > 0">{{ j.stalled }}</td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        }
      </section>
    }
  `,
})
export default class DashboardPage {
  readonly periods: Period[] = [1, 7, 30];
  readonly days = signal<Period>(7);
  readonly dash = httpResource<Dashboard>(() => ({ url: '/api/dashboard', params: { days: this.days() } }));
  readonly data = computed(() => (this.dash.hasValue() ? this.dash.value() : null));
  readonly alerts = computed(() => (this.data() ? attention(this.data()!) : []));
  readonly totals = computed(() => sendTotals(this.data()?.sends24h ?? []));
  readonly reasons = computed(() => blockReasons(this.data()?.sends24h ?? []));
  readonly journeys = computed(() => journeyRows(this.data()?.journeys ?? []));
  readonly optIns = computed(() => optInsByDay(this.data()?.optIns ?? []));
  readonly period = computed(() => periodLabel(this.data()?.days ?? this.days()));
  readonly previous = computed(() => previousLabel(this.data()?.days ?? this.days()));
  readonly revenueChange = computed(() => {
    const h = this.data()?.headline;
    return h ? change(h.revenuePaise, h.prevRevenuePaise) : null;
  });
  readonly roiValue = computed(() => {
    const h = this.data()?.headline;
    return h ? roi(h.revenuePaise, h.spendPaise) : null;
  });
  readonly waitingFor = computed(() => since(this.data()?.inbox.oldestWaitingAt ?? null));
  readonly approved = computed(() => this.#templates((s) => s === 'APPROVED'));
  readonly pendingTemplates = computed(() => this.#templates((s) => s !== 'APPROVED'));
  readonly unratedTemplates = computed(() =>
    (this.data()?.waTemplates ?? []).some((t) => t.status === 'APPROVED' && t.quality === 'UNKNOWN'));
  readonly #max = computed(() => Math.max(1, ...this.optIns().flatMap((o) => [o.whatsapp + o.push, o.out])));

  readonly pct = budgetPct;
  readonly rate = rate;
  readonly journeyName = journeyName;
  readonly logChannel = logChannel;
  readonly qualityText = qualityText;
  readonly tierText = tierText;
  readonly tierLimit = tierLimit;

  constructor() {
    const timer = setInterval(() => this.dash.reload(), 30_000);
    inject(DestroyRef).onDestroy(() => clearInterval(timer));
  }

  channelName(c: string): string {
    return CHANNEL_NAMES[c] ?? c;
  }

  usedPct(used: number, limit: number): number {
    return Math.min(100, Math.round((Number(used) / limit) * 100));
  }

  /** Bar height in px, scaled to the busiest day. */
  bar(n: number): number {
    return n ? Math.max(2, Math.round((n / this.#max()) * 60)) : 0;
  }

  #templates(match: (status: string) => boolean): number {
    return (this.data()?.waTemplates ?? []).filter((t) => match(t.status)).reduce((a, t) => a + Number(t.templates), 0);
  }
}
