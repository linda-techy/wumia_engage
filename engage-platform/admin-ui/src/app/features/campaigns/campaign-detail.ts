import { httpResource } from '@angular/common/http';
import { Component, computed, DestroyRef, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TokenService } from '../../core/auth/token.service';
import { Api } from '../../shared/api';
import { InrPipe, IstPipe, problemDetail } from '../../shared/format';
import { ErrorState, LoadingState } from '../../shared/states';
import { CUSTOM_PUSH_TEMPLATE, PushPreview } from './push-preview';
import { CampaignReport } from '../../core/api/models';
import { rate } from '../analytics/analytics-model';
import { Campaign, canApprove, canArchive, canArm, canCancel, canDelete, canEdit, canEstimate, canPause, canResume, Me, progress, stageOf, statusLine, Verdict } from './campaign-rules';
import { Router } from '@angular/router';

const LIVE = ['SCHEDULED', 'RUNNING'];

/**
 * One campaign: the dry-run breakdown (phase-6 §5: "the dry run is the
 * product"), approval, arming, and live progress while it runs. Each button
 * is disabled when the server would refuse, and says why.
 */
@Component({
  selector: 'app-campaign-detail',
  imports: [RouterLink, IstPipe, InrPipe, LoadingState, ErrorState, PushPreview],
  template: `
    <a routerLink="/campaigns" class="text-sm text-slate-600 underline">← Campaigns</a>
    @if (campaign.isLoading() && !campaign.hasValue()) {
      <app-loading />
    } @else if (campaign.error() && !campaign.hasValue()) {
      <app-error [error]="campaign.error()" [retryable]="false" />
    } @else if (c(); as c) {
      <header class="mb-6 mt-3 flex items-start justify-between">
        <div>
          <h1 class="text-2xl font-semibold">{{ c.name }}</h1>
          <p class="text-sm text-slate-500">
            {{ c.channel }} · <span class="font-mono">{{ c.templateKey }}</span>
            @if (c.followUpChannel) { → {{ c.followUpChannel }} after {{ (c.followUpAfterMinutes ?? 0) / 60 }} h (<span class="font-mono">{{ c.followUpTemplateKey }}</span>) }
            · segment {{ c.segmentName ?? '—' }} · by {{ c.createdBy }}
          </p>
        </div>
        <span class="badge text-sm" [class.badge-green]="c.status === 'RUNNING'" [class.badge-red]="c.status === 'PAUSED'">
          {{ c.status }}{{ c.pausedReason ? ' (' + c.pausedReason + ')' : '' }}{{ c.archivedAt ? ' · archived' : '' }}</span>
      </header>
      <p class="mb-3 text-sm text-slate-700">{{ line() }}</p>

      @if (notice()) { <p class="mb-4 rounded bg-emerald-50 p-3 text-sm text-emerald-800" role="status">{{ notice() }}</p> }
      @if (error()) { <p class="mb-4 rounded bg-red-50 p-3 text-sm text-red-800" role="alert">{{ error() }}</p> }

      <!-- Only the actions that make sense now; a disabled one says why in text, not in a hover tooltip. -->
      <section class="mb-6 flex flex-wrap items-center gap-2">
        @switch (stage()) {
          @case ('prepare') {
            @if (v().edit.allowed) { <a class="btn" [routerLink]="['/campaigns', c.id, 'edit']">Edit</a> }
            <button type="button" class="btn" [disabled]="busy() || !v().estimate.allowed" (click)="act('estimate')">Run dry run</button>
            @if (c.status === 'PENDING_APPROVAL') {
              <button type="button" class="btn" [disabled]="busy() || !v().approve.allowed || !reviewed()" (click)="act('approve')">Approve</button>
            }
            <button type="button" class="btn-primary" [disabled]="busy() || !v().arm.allowed" (click)="act('start')">Arm and start</button>
            @if (v().del.allowed) {
              <button type="button" class="btn ml-auto text-red-700" [disabled]="busy()" (click)="remove()">Delete draft</button>
            }
          }
          @case ('live') {
            <button type="button" class="btn" [disabled]="busy() || !v().pause.allowed" (click)="act('pause')">Pause</button>
            <button type="button" class="btn text-red-700" [disabled]="busy() || !v().cancel.allowed" (click)="act('cancel')">Cancel campaign</button>
          }
          @case ('paused') {
            <button type="button" class="btn-primary" [disabled]="busy() || !v().resume.allowed" (click)="act('resume')">Resume</button>
            <button type="button" class="btn text-red-700" [disabled]="busy() || !v().cancel.allowed" (click)="act('cancel')">Cancel campaign</button>
          }
          @case ('done') {
            @if (c.archivedAt) {
              <button type="button" class="btn" [disabled]="busy() || !v().archive.allowed" (click)="act('unarchive')">Unarchive</button>
            } @else {
              <button type="button" class="btn" [disabled]="busy() || !v().archive.allowed" (click)="act('archive')">Archive</button>
            }
          }
        }
      </section>
      @if (reasons().length) {
        <p class="-mt-4 mb-6 text-xs text-slate-500">{{ reasons().join(' · ') }}</p>
      }

      @if (['PENDING_APPROVAL', 'READY', 'DRAFT'].includes(c.status)) {
        <section class="card mb-6" aria-labelledby="review-h">
          <h2 id="review-h" class="mb-1 font-semibold">Review: what goes out</h2>
          <p class="mb-4 text-xs text-slate-500">Check this before approving. Approving re-runs the dry run, so it is given on today's numbers.</p>
          <div class="flex flex-wrap gap-6">
            @if (c.channel === 'push') {
              <app-push-preview [title]="c.templateKey === custom ? (c.vars['title'] ?? '') : 'Title from ' + c.templateKey"
                                [body]="c.templateKey === custom ? (c.vars['body'] ?? '') : 'Text from the template, with the variables below'"
                                [image]="c.vars['image'] ?? null" [url]="c.vars['url'] ?? null" />
            }
            <dl class="min-w-64 flex-1 space-y-2 text-sm">
              <div><dt class="label">Opens</dt><dd class="break-all">
                @if (c.vars['url']) { <a [href]="c.vars['url']" target="_blank" rel="noopener" class="underline">{{ c.vars['url'] }}</a> } @else { — }</dd></div>
              <div><dt class="label">Template</dt><dd class="font-mono text-xs">{{ c.templateKey }}</dd></div>
              @for (r of reviewVars(c); track r[0]) { <div><dt class="label">{{ r[0] }}</dt><dd>{{ r[1] }}</dd></div> }
              <div><dt class="label">Audience</dt><dd>{{ c.estimate ? c.estimate.willReceive + ' of ' + c.estimate.audience + ' in ' + (c.segmentName ?? 'the segment') : 'run the dry run' }}</dd></div>
              <div><dt class="label">Cost</dt><dd>{{ c.estimate ? (c.estimate.costPaise | inr) : '—' }}</dd></div>
            </dl>
          </div>
          @if (c.status === 'PENDING_APPROVAL') {
            <label class="mt-4 flex items-start gap-2 text-sm">
              <input type="checkbox" class="mt-1" [checked]="reviewed()" (change)="reviewed.set(checkedOf($event))" />
              <span>I have checked the text, the poster, the link and the audience, and they are right to send.</span>
            </label>
          }
        </section>
      }

      <section class="mb-6 grid gap-4 md:grid-cols-4">
        <div class="card"><p class="label">Rate</p><p class="stat">{{ c.sendRatePerMinute }}/min</p></div>
        <div class="card"><p class="label">Holdout</p><p class="stat">{{ c.holdoutPct }}%</p></div>
        <div class="card"><p class="label">Budget cap</p><p class="stat">{{ c.budgetCapPaise | inr }}</p></div>
        <div class="card"><p class="label">Starts</p><p class="mt-1 text-sm font-semibold">{{ c.scheduledAt ? (c.scheduledAt | ist) : 'when armed' }}</p></div>
      </section>

      @if (c.estimate; as e) {
        <section class="mb-6">
          <h2 class="mb-1 font-semibold">Dry run</h2>
          <p class="mb-3 text-xs text-slate-500">{{ e.estimatedAt | ist }} · real policy decisions, nothing sent{{ c.approvedBy ? ' · approved by ' + c.approvedBy : '' }}</p>
          <div class="grid gap-4 md:grid-cols-3">
            <div class="card"><p class="label">Audience</p><p class="stat">{{ e.audience }}</p></div>
            <div class="card"><p class="label">Will receive</p><p class="stat text-emerald-700">{{ e.willReceive }}</p></div>
            <div class="card"><p class="label">Estimated cost</p><p class="stat">{{ e.costPaise | inr }}</p></div>
          </div>
          <table class="table mt-4">
            <thead><tr><th>Not receiving</th><th>Why</th><th class="num">People</th></tr></thead>
            <tbody>
              @if (e.holdout) { <tr><td>Campaign holdout</td><td>measured, never messaged</td><td class="num">{{ e.holdout }}</td></tr> }
              @for (r of entries(e.excluded); track r[0]) { <tr><td class="font-mono text-xs">{{ r[0] }}</td><td>campaign rule</td><td class="num">{{ r[1] }}</td></tr> }
              @for (r of entries(e.blocked); track r[0]) { <tr><td class="font-mono text-xs">{{ r[0] }}</td><td>policy</td><td class="num">{{ r[1] }}</td></tr> }
              @for (r of entries(e.deferred); track r[0]) { <tr><td class="font-mono text-xs">{{ r[0] }}</td><td>later (deferred)</td><td class="num">{{ r[1] }}</td></tr> }
            </tbody>
          </table>
        </section>
      }

      @if (c.startedAt && report.hasValue()) {
        @let r = report.value();
        <section class="mb-6" aria-labelledby="results-h">
          <h2 id="results-h" class="mb-2 font-semibold">Results</h2>
          <div class="grid gap-3 sm:grid-cols-3 lg:grid-cols-6">
            <div class="card"><p class="label">Sent</p><p class="stat">{{ r.total.sent }}</p></div>
            <div class="card"><p class="label">Delivered</p><p class="stat">{{ rate(r.total.delivered, r.total.sent) }}</p></div>
            <div class="card"><p class="label">Clicked</p><p class="stat">{{ rate(r.total.clicked, r.total.delivered) }}</p><p class="text-xs text-slate-500">{{ r.total.clicked }}</p></div>
            <div class="card"><p class="label">Orders</p><p class="stat">{{ r.total.orders }}</p></div>
            <div class="card"><p class="label">Revenue</p><p class="stat">{{ r.total.revenuePaise | inr }}</p></div>
            <div class="card"><p class="label">Cost</p><p class="stat">{{ r.total.costPaise | inr }}</p></div>
          </div>
          @if (r.channels.length > 1) {
            <table class="table mt-3">
              <thead><tr><th>Channel</th><th class="num">Sent</th><th class="num">Delivered</th><th class="num">Read</th><th class="num">Clicked</th><th class="num">Orders</th><th class="num">Revenue</th></tr></thead>
              <tbody>@for (f of r.channels; track f.key) {
                <tr><td>{{ f.key }}</td><td class="num">{{ f.sent }}</td><td class="num">{{ rate(f.delivered, f.sent) }}</td>
                  <td class="num">{{ f.read ? rate(f.read, f.delivered) : '—' }}</td><td class="num">{{ rate(f.clicked, f.delivered) }}</td>
                  <td class="num">{{ f.orders }}</td><td class="num">{{ f.revenuePaise | inr }}</td></tr>
              }</tbody>
            </table>
          }
          @if (r.holdout.control > 0) {
            <p class="mt-3 text-sm">
              Held back: {{ r.holdout.control }} people. In the {{ r.windowDays }} days after the start, messaged customers spent
              {{ r.holdout.revenuePerTreatedPaise | inr }} each against {{ r.holdout.revenuePerControlPaise | inr }} for those held back
              @if (r.holdout.enoughData && r.holdout.liftPct !== null) {
                ({{ r.holdout.liftPct > 0 ? '+' : '' }}{{ r.holdout.liftPct.toFixed(1) }}%,
                likely between {{ signed(r.holdout.liftLowPct) }} and {{ signed(r.holdout.liftHighPct) }}).
                @if (chance(r.holdout)) { <strong>That range includes zero: the difference could still be chance.</strong> }
                @else if ((r.holdout.liftPct ?? 0) > 0) { <strong>A real lift, not chance.</strong> }
                @else { <strong>Messaged customers spent less: worth a look.</strong> }
              } @else {
                (too few people yet to tell; 30 in each group are needed).
              }
            </p>
          }
          <p class="mt-1 text-xs text-slate-500">Orders count when the customer clicked within {{ r.windowDays }} days, or got or read the message shortly before.</p>
        </section>
      }

      @if (prog().total > 0) {
        <section class="mb-6">
          <h2 class="mb-2 font-semibold">Progress{{ live() ? ' (live)' : '' }}</h2>
          <div class="h-3 overflow-hidden rounded bg-slate-100" role="progressbar" [attr.aria-valuenow]="prog().pct" aria-valuemin="0" aria-valuemax="100">
            <div class="h-full bg-emerald-500" [style.width.%]="prog().pct"></div>
          </div>
          <p class="mt-2 text-sm">{{ prog().done }} of {{ prog().total }} handled ·
            @for (r of entries(c.recipients); track r[0]) { {{ r[0] }} {{ r[1] }}{{ $last ? '' : ' · ' }} }
          </p>
        </section>
      }
    }
  `,
})
export default class CampaignDetailPage {
  readonly custom = CUSTOM_PUSH_TEMPLATE;
  /** Results once it has started (GET /api/campaigns/{id}/report). */
  readonly report = httpResource<CampaignReport>(() =>
    this.campaign.value()?.startedAt ? `/api/campaigns/${encodeURIComponent(this.id())}/report` : undefined);
  rate = rate;

  signed(v: number | null): string {
    return v === null ? '—' : `${v > 0 ? '+' : ''}${v.toFixed(0)}%`;
  }

  /** The 95% range of the lift spans zero. */
  chance(h: CampaignReport['holdout']): boolean {
    return h.liftLowPct === null || h.liftHighPct === null || (h.liftLowPct < 0 && h.liftHighPct > 0);
  }
  /** The review checklist; Approve stays off until it is ticked. */
  readonly reviewed = signal(false);

  checkedOf(e: Event): boolean {
    return (e.target as HTMLInputElement).checked;
  }

  /** Variables worth reading in the review; the preview already shows title, text, poster and link. */
  reviewVars(c: Campaign): [string, string][] {
    const shown = new Set(['url', 'image', ...(c.channel === 'push' && c.templateKey === CUSTOM_PUSH_TEMPLATE ? ['title', 'body'] : [])]);
    return Object.entries(c.vars ?? {}).filter(([k]) => !shown.has(k));
  }

  readonly #api = inject(Api);
  readonly #tokens = inject(TokenService);

  readonly id = input.required<string>();
  readonly campaign = httpResource<Campaign>(() => `/api/campaigns/${encodeURIComponent(this.id())}`);
  readonly c = computed(() => (this.campaign.hasValue() ? this.campaign.value() : null));
  readonly now = signal(Date.now());
  readonly busy = signal(false);
  readonly notice = signal<string | null>(null);
  readonly error = signal<string | null>(null);

  readonly me = computed<Me>(() => ({
    email: this.#tokens.operator()?.email ?? '',
    canEdit: this.#tokens.has('CAMPAIGN_EDIT'),
    canSend: this.#tokens.has('CAMPAIGN_SEND'),
  }));
  readonly v = computed(() => {
    const c = this.c()!;
    const me = this.me();
    return {
      edit: canEdit(c, me),
      estimate: canEstimate(c, me),
      approve: canApprove(c, me),
      arm: canArm(c, me, this.now()),
      pause: canPause(c, me),
      resume: canResume(c, me),
      cancel: canCancel(c, me),
      del: canDelete(c, me),
      archive: canArchive(c, me),
    };
  });
  readonly stage = computed(() => stageOf(this.c() ?? { status: 'DRAFT' }));
  readonly line = computed(() => (this.c() ? statusLine(this.c()!, (iso) => new Date(iso).toLocaleString('en-IN', {
    timeZone: 'Asia/Kolkata', day: 'numeric', month: 'short', hour: 'numeric', minute: '2-digit' })) : ''));
  /** Why the shown buttons are disabled, in words. */
  readonly reasons = computed(() => {
    const v = this.v();
    const out: string[] = [];
    switch (this.stage()) {
      case 'prepare':
        if (this.c()?.status === 'PENDING_APPROVAL' && !this.reviewed()) out.push('Approve: tick the review checklist first');
        else if (this.c()?.status === 'PENDING_APPROVAL' && !v.approve.allowed) out.push('Approve: ' + this.why(v.approve));
        if (!v.arm.allowed) out.push('Arm: ' + this.why(v.arm));
        break;
      case 'live': if (!v.pause.allowed) out.push(this.why(v.pause)); break;
      case 'paused': if (!v.resume.allowed) out.push('Resume: ' + this.why(v.resume)); break;
      case 'done': if (!v.archive.allowed) out.push(this.why(v.archive)); break;
    }
    return out;
  });
  readonly live = computed(() => LIVE.includes(this.c()?.status ?? ''));
  readonly prog = computed(() => progress(this.c() ?? { recipients: {} }));

  constructor() {
    // Live progress every 5 s while it runs; the clock ticks for the 30-minute dry-run rule.
    const timer = setInterval(() => {
      this.now.set(Date.now());
      if (this.live()) {
        this.campaign.reload();
        this.report.reload();
      }
    }, 5000);
    inject(DestroyRef).onDestroy(() => clearInterval(timer));
  }

  why(v: Verdict): string {
    return v.allowed ? '' : v.why;
  }

  entries(m: Record<string, number> | null | undefined): [string, number][] {
    return Object.entries(m ?? {});
  }

  readonly #router = inject(Router);

  /** Deletes a campaign that never sent, then back to the list. */
  async remove(): Promise<void> {
    if (!confirm('Delete this draft? It never sent, so nothing else is affected.')) return;
    this.busy.set(true);
    this.error.set(null);
    try {
      await this.#api.delete(`/api/campaigns/${encodeURIComponent(this.id())}`);
      await this.#router.navigate(['/campaigns']);
    } catch (e) {
      this.error.set(problemDetail(e, 'Could not delete it.'));
    } finally {
      this.busy.set(false);
    }
  }

  async act(action: 'estimate' | 'approve' | 'start' | 'pause' | 'resume' | 'cancel' | 'archive' | 'unarchive'): Promise<void> {
    if (action === 'cancel' && !confirm('Cancel this campaign? People not yet reached will not be.')) return;
    this.busy.set(true);
    this.error.set(null);
    this.notice.set(null);
    try {
      await this.#api.post(`/api/campaigns/${encodeURIComponent(this.id())}/${action}`, {});
      this.notice.set(
        { estimate: 'Dry run done.', approve: 'Approved.', start: 'Armed.', pause: 'Paused.', resume: 'Resumed.', cancel: 'Cancelled.',
          archive: 'Archived: hidden from the campaign list (Show archived to see it).', unarchive: 'Back in the campaign list.' }[action],
      );
      if (action === 'estimate') this.reviewed.set(false);   // new numbers: review them again
      this.now.set(Date.now());
      this.campaign.reload();
    } catch (e) {
      this.error.set(problemDetail(e, 'That did not work.'));
    } finally {
      this.busy.set(false);
    }
  }
}
