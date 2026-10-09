import { httpResource } from '@angular/common/http';
import { Component, computed, DestroyRef, inject } from '@angular/core';
import { InrPipe, IstPipe } from '../../shared/format';
import { EmptyState, ErrorState, LoadingState } from '../../shared/states';
import { blockReasons, budgetPct, Dashboard, optOutsByDay, sendTotals } from './dashboard-model';

/**
 * The operations dashboard (06-admin-ui §Dashboard), polled every 30 s. The
 * kill switches are in the header on every page; this screen is spend,
 * block reasons, journeys, opt-outs, then push and WhatsApp health.
 */
@Component({
  selector: 'app-dashboard',
  imports: [InrPipe, IstPipe, LoadingState, ErrorState, EmptyState],
  template: `
    <header class="mb-6 flex items-end justify-between">
      <div>
        <h1 class="text-2xl font-semibold">Dashboard</h1>
        <p class="text-sm text-slate-500">Refreshes every 30 seconds.@if (data()) { Last: {{ data()!.generatedAt | ist }}. }</p>
      </div>
      <button type="button" class="btn" (click)="dash.reload()">Refresh</button>
    </header>

    @if (dash.isLoading() && !dash.hasValue()) {
      <app-loading />
    } @else if (dash.error() && !dash.hasValue()) {
      <app-error [error]="dash.error()" (retry)="dash.reload()" />
    } @else if (data(); as d) {
      <!-- 1. Spend today vs budget -->
      <section class="mb-8">
        <h2 class="mb-3 font-semibold">Spend today (IST)</h2>
        @if (d.spendToday.length === 0) {
          <app-empty text="No paid channel has a budget or spend yet." />
        } @else {
          <div class="grid gap-4 md:grid-cols-2">
            @for (s of d.spendToday; track s.channel + s.category) {
              <div class="card">
                <div class="flex justify-between text-sm">
                  <span class="font-medium">{{ s.channel }} · {{ s.category }}</span>
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
            }
          </div>
        }
      </section>

      <!-- 2. Sends and block reasons, 24 h -->
      <section class="mb-8 grid gap-6 lg:grid-cols-2">
        <div>
          <h2 class="mb-3 font-semibold">Last 24 hours</h2>
          <div class="grid grid-cols-4 gap-3">
            <div class="card"><p class="label">Sent</p><p class="stat">{{ totals().sent }}</p></div>
            <div class="card"><p class="label">Blocked</p><p class="stat">{{ totals().blocked }}</p></div>
            <div class="card"><p class="label">Deferred</p><p class="stat">{{ totals().deferred }}</p></div>
            <div class="card"><p class="label">Failed</p><p class="stat" [class.text-red-700]="totals().failed > 0">{{ totals().failed }}</p></div>
          </div>
        </div>
        <div>
          <h2 class="mb-3 font-semibold">Block reasons, 24 h</h2>
          @if (reasons().length === 0) {
            <app-empty text="Nothing blocked or deferred." />
          } @else {
            <table class="table">
              <thead><tr><th>Reason</th><th>Outcome</th><th class="num">Sends</th></tr></thead>
              <tbody>
                @for (r of reasons(); track r.status + r.reason) {
                  <tr><td class="font-mono text-xs">{{ r.reason }}</td><td>{{ r.status }}</td><td class="num">{{ r.sends }}</td></tr>
                }
              </tbody>
            </table>
          }
        </div>
      </section>

      <!-- 3. Journey health -->
      <section class="mb-8">
        <h2 class="mb-3 font-semibold">Journeys</h2>
        @if (d.journeys.length === 0) {
          <app-empty text="No journey runs in the last 24 hours." />
        } @else {
          <table class="table">
            <thead><tr><th>Journey</th><th class="num">Entered 24 h</th><th class="num">Succeeded</th><th class="num">Exhausted</th><th class="num">Failed</th><th class="num">Stalled now</th></tr></thead>
            <tbody>
              @for (j of d.journeys; track j.intentKey) {
                <tr>
                  <td class="font-mono text-xs">{{ j.intentKey }}</td>
                  <td class="num">{{ j.entered24h }}</td>
                  <td class="num">{{ j.succeeded24h }}</td>
                  <td class="num">{{ j.exhausted24h }}</td>
                  <td class="num" [class.text-red-700]="j.failed24h > 0">{{ j.failed24h }}</td>
                  <td class="num" [class.font-semibold]="j.stalled > 0" [class.text-red-700]="j.stalled > 0">{{ j.stalled }}</td>
                </tr>
              }
            </tbody>
          </table>
        }
      </section>

      <!-- 4. Opt-outs -->
      <section class="mb-8">
        <h2 class="mb-1 font-semibold">Opt-ins and opt-outs per day</h2>
        <p class="mb-3 text-xs text-slate-500">Rising opt-outs come before a WhatsApp quality drop, usually by a few days.</p>
        @if (consent().length === 0) {
          <app-empty text="No consent changes in the last 30 days." />
        } @else {
          <div class="flex items-end gap-1 overflow-x-auto rounded-lg border border-slate-200 bg-white p-4" aria-label="Opt-ins and opt-outs per day">
            @for (c of consent(); track c.day) {
              <div class="flex w-10 shrink-0 flex-col items-center gap-1 text-xs" [title]="c.day + ': ' + c.optIns + ' in, ' + c.optOuts + ' out'">
                <span class="tabular-nums text-red-700">{{ c.optOuts || '' }}</span>
                <div class="w-3 rounded-t bg-red-400" [style.height.px]="bar(c.optOuts)"></div>
                <div class="w-3 rounded-b bg-emerald-400" [style.height.px]="bar(c.optIns)"></div>
                <span class="tabular-nums text-emerald-700">{{ c.optIns || '' }}</span>
                <span class="text-slate-500">{{ c.day.slice(5) }}</span>
              </div>
            }
          </div>
        }
      </section>

      <!-- 5. Push and WhatsApp health -->
      <section class="mb-8 grid gap-6 lg:grid-cols-2">
        <div>
          <h2 class="mb-3 font-semibold">Push subscribers</h2>
          @if (d.pushDevices.length === 0) {
            <app-empty text="No push tokens yet." />
          } @else {
            <table class="table mb-4">
              <thead><tr><th>Browser</th><th>State</th><th class="num">Devices</th></tr></thead>
              <tbody>
                @for (p of d.pushDevices; track p.browser + p.state) {
                  <tr><td>{{ p.browser }}</td><td>{{ p.state }}</td><td class="num">{{ p.devices }}</td></tr>
                }
              </tbody>
            </table>
          }
          <h3 class="mb-2 text-sm font-semibold">Prompt funnel, 7 days</h3>
          @if (d.pushFunnel7d.length === 0) {
            <app-empty text="No prompts shown in 7 days." />
          } @else {
            <table class="table">
              <thead><tr><th>Surface</th><th class="num">Shown</th><th class="num">Accepted</th><th class="num">Granted</th><th class="num">Token</th><th class="num">iOS → WA</th></tr></thead>
              <tbody>
                @for (f of d.pushFunnel7d; track f.surface) {
                  <tr>
                    <td>{{ f.surface }}</td><td class="num">{{ f.softShown }}</td><td class="num">{{ f.softAccepted }}</td>
                    <td class="num">{{ f.nativeGranted }}</td><td class="num">{{ f.tokenMinted }}</td><td class="num">{{ f.iosRedirected }}</td>
                  </tr>
                }
              </tbody>
            </table>
          }
        </div>
        <div>
          <h2 class="mb-3 font-semibold">WhatsApp</h2>
          <h3 class="mb-2 text-sm font-semibold">Who we know can receive it</h3>
          @if (d.capability.length === 0) {
            <app-empty text="No capability learned yet: it comes from order-tracking messages." />
          } @else {
            <table class="table mb-4">
              <thead><tr><th>Channel</th><th>Capability</th><th class="num">People</th></tr></thead>
              <tbody>
                @for (c of d.capability; track c.channel + c.state) {
                  <tr><td>{{ c.channel }}</td><td>{{ c.state }}</td><td class="num">{{ c.identities }}</td></tr>
                }
              </tbody>
            </table>
          }
          <h3 class="mb-2 text-sm font-semibold">Templates</h3>
          @if (d.waTemplates.length === 0) {
            <app-empty text="No WhatsApp templates synced yet (Phase 4)." />
          } @else {
            <table class="table mb-4">
              <thead><tr><th>Status</th><th>Quality</th><th class="num">Templates</th></tr></thead>
              <tbody>
                @for (t of d.waTemplates; track t.status + t.quality) {
                  <tr><td>{{ t.status }}</td><td [class.text-red-700]="t.quality === 'RED'">{{ t.quality }}</td><td class="num">{{ t.templates }}</td></tr>
                }
              </tbody>
            </table>
          }
          @for (m of d.waTemplateMismatches; track m.key + m.language) {
            <p class="mb-1 rounded bg-red-50 p-2 text-sm text-red-800" role="alert">
              {{ m.key }} ({{ m.language }}) was approved as {{ m.approvedCategory }}, requested as {{ m.requestedCategory }}.
            </p>
          }
          <p class="mt-2 text-xs text-slate-500">Number quality and messaging tier arrive with Phase 4.</p>
        </div>
      </section>
    }
  `,
})
export default class DashboardPage {
  readonly dash = httpResource<Dashboard>(() => '/api/dashboard');
  readonly data = computed(() => (this.dash.hasValue() ? this.dash.value() : null));
  readonly totals = computed(() => sendTotals(this.data()?.sends24h ?? []));
  readonly reasons = computed(() => blockReasons(this.data()?.sends24h ?? []));
  readonly consent = computed(() => optOutsByDay(this.data()?.consentDaily ?? []));
  readonly #max = computed(() => Math.max(1, ...this.consent().flatMap((c) => [c.optIns, c.optOuts])));

  readonly pct = budgetPct;

  constructor() {
    const timer = setInterval(() => this.dash.reload(), 30_000);
    inject(DestroyRef).onDestroy(() => clearInterval(timer));
  }

  /** Bar height in px, scaled to the busiest day. */
  bar(n: number): number {
    return n ? Math.max(2, Math.round((n / this.#max()) * 60)) : 0;
  }
}
