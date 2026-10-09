import { httpResource } from '@angular/common/http';
import { Component, computed, DestroyRef, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TokenService } from '../../core/auth/token.service';
import { Api } from '../../shared/api';
import { InrPipe, IstPipe, problemDetail } from '../../shared/format';
import { ErrorState, LoadingState } from '../../shared/states';
import { Campaign, canApprove, canArm, canCancel, canEdit, canEstimate, canPause, canResume, Me, progress, Verdict } from './campaign-rules';

const LIVE = ['SCHEDULED', 'RUNNING'];

/**
 * One campaign: the dry-run breakdown (phase-6 §5: "the dry run is the
 * product"), approval, arming, and live progress while it runs. Each button
 * is disabled when the server would refuse, and says why.
 */
@Component({
  selector: 'app-campaign-detail',
  imports: [RouterLink, IstPipe, InrPipe, LoadingState, ErrorState],
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
          {{ c.status }}{{ c.pausedReason ? ' (' + c.pausedReason + ')' : '' }}</span>
      </header>

      @if (notice()) { <p class="mb-4 rounded bg-emerald-50 p-3 text-sm text-emerald-800" role="status">{{ notice() }}</p> }
      @if (error()) { <p class="mb-4 rounded bg-red-50 p-3 text-sm text-red-800" role="alert">{{ error() }}</p> }

      <section class="mb-6 flex flex-wrap gap-2">
        @if (v().edit.allowed) {
          <a class="btn" [routerLink]="['/campaigns', c.id, 'edit']">Edit</a>
        }
        <button type="button" class="btn" [disabled]="busy() || !v().estimate.allowed" [title]="why(v().estimate)" (click)="act('estimate')">Run dry run</button>
        <button type="button" class="btn" [disabled]="busy() || !v().approve.allowed" [title]="why(v().approve)" (click)="act('approve')">Approve</button>
        <button type="button" class="btn-primary" [disabled]="busy() || !v().arm.allowed" [title]="why(v().arm)" (click)="act('start')">Arm and start</button>
        <button type="button" class="btn" [disabled]="busy() || !v().pause.allowed" [title]="why(v().pause)" (click)="act('pause')">Pause</button>
        <button type="button" class="btn" [disabled]="busy() || !v().resume.allowed" [title]="why(v().resume)" (click)="act('resume')">Resume</button>
        <button type="button" class="btn" [disabled]="busy() || !v().cancel.allowed" [title]="why(v().cancel)" (click)="act('cancel')">Cancel</button>
      </section>
      @if (!v().arm.allowed && ['READY', 'PENDING_APPROVAL', 'DRAFT'].includes(c.status)) {
        <p class="-mt-4 mb-6 text-xs text-slate-500">Arm: {{ why(v().arm) }}.@if (!v().approve.allowed && c.status === 'PENDING_APPROVAL') { Approve: {{ why(v().approve) }}. }</p>
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
    };
  });
  readonly live = computed(() => LIVE.includes(this.c()?.status ?? ''));
  readonly prog = computed(() => progress(this.c() ?? { recipients: {} }));

  constructor() {
    // Live progress every 5 s while it runs; the clock ticks for the 30-minute dry-run rule.
    const timer = setInterval(() => {
      this.now.set(Date.now());
      if (this.live()) this.campaign.reload();
    }, 5000);
    inject(DestroyRef).onDestroy(() => clearInterval(timer));
  }

  why(v: Verdict): string {
    return v.allowed ? '' : v.why;
  }

  entries(m: Record<string, number> | null | undefined): [string, number][] {
    return Object.entries(m ?? {});
  }

  async act(action: 'estimate' | 'approve' | 'start' | 'pause' | 'resume' | 'cancel'): Promise<void> {
    if (action === 'cancel' && !confirm('Cancel this campaign? People not yet reached will not be.')) return;
    this.busy.set(true);
    this.error.set(null);
    this.notice.set(null);
    try {
      await this.#api.post(`/api/campaigns/${encodeURIComponent(this.id())}/${action}`, {});
      this.notice.set(
        { estimate: 'Dry run done.', approve: 'Approved.', start: 'Armed.', pause: 'Paused.', resume: 'Resumed.', cancel: 'Cancelled.' }[action],
      );
      this.now.set(Date.now());
      this.campaign.reload();
    } catch (e) {
      this.error.set(problemDetail(e, 'That did not work.'));
    } finally {
      this.busy.set(false);
    }
  }
}
