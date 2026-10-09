import { httpResource } from '@angular/common/http';
import { Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { Api, istInputToIso } from '../../shared/api';
import { problemDetail } from '../../shared/format';
import { ErrorState, LoadingState } from '../../shared/states';
import { SegmentRow } from '../segments/segments';
import { Campaign } from './campaign-rules';

interface Var {
  key: string;
  value: string;
}

/** "2026-10-09T18:30:00Z" → "2026-10-10T00:00" in IST, for a datetime-local box. */
function isoToIstInput(iso: string | null): string {
  if (!iso) return '';
  const d = new Date(new Date(iso).getTime() + 330 * 60 * 1000);
  return d.toISOString().slice(0, 16);
}

/**
 * The campaign composer: template → segment → variables → schedule and
 * limits. Saving makes a DRAFT (or sends an edited one back to DRAFT); the
 * dry run, approval and arming happen on the campaign's page. Templates are
 * only those the server allows for the channel.
 */
@Component({
  selector: 'app-campaign-composer',
  imports: [RouterLink, LoadingState, ErrorState],
  template: `
    <a [routerLink]="id() ? ['/campaigns', id()] : ['/campaigns']" class="text-sm text-slate-600 underline">← Back</a>
    <header class="mb-6 mt-3">
      <h1 class="text-2xl font-semibold">{{ id() ? 'Edit campaign' : 'New campaign' }}</h1>
      <p class="text-sm text-slate-500">Copy comes from reviewed templates; you fill in their variables.</p>
    </header>
    @if (id() && existing.isLoading()) {
      <app-loading />
    } @else if (existing.error()) {
      <app-error [error]="existing.error()" [retryable]="false" />
    } @else {
      <form class="max-w-3xl space-y-6" (submit)="save($event)">
        <section class="card grid grid-cols-2 gap-4">
          <label class="col-span-2 text-sm"><span class="mb-1 block font-medium">Name</span>
            <input class="input" [value]="name()" (input)="name.set(val($event))" placeholder="Onam edit, Kerala" /></label>
          <label class="text-sm"><span class="mb-1 block font-medium">Channel</span>
            <select class="input" [value]="channel()" (change)="setChannel(val($event))">
              <option value="push">Push</option>
              <option value="whatsapp">WhatsApp</option>
            </select></label>
          <label class="text-sm"><span class="mb-1 block font-medium">Template</span>
            <select class="input" [value]="templateKey()" (change)="templateKey.set(val($event))">
              <option value="" disabled>Pick a template</option>
              @for (t of templates.value() ?? []; track t.key) { <option [value]="t.key">{{ t.key }}</option> }
            </select>
            @if (templates.hasValue() && templates.value().length === 0) {
              <span class="mt-1 block text-xs text-amber-800">No {{ channel() }} template is eligible.{{ channel() === 'whatsapp' ? ' WhatsApp needs APPROVED marketing templates (Phase 4).' : '' }}</span>
            }
          </label>
          <label class="col-span-2 text-sm"><span class="mb-1 block font-medium">Segment</span>
            <select class="input" [value]="segmentId()" (change)="segmentId.set(val($event))">
              <option value="" disabled>Pick a segment</option>
              @for (s of segments.value() ?? []; track s.id) { <option [value]="s.id">{{ s.name }} ({{ s.lastSize ?? '?' }})</option> }
            </select></label>
        </section>

        <section class="card space-y-2">
          <h2 class="text-sm font-semibold">Template variables</h2>
          <p class="text-xs text-slate-500">For example collection, festival, url (https://), image.</p>
          @for (v of vars(); track $index; let i = $index) {
            <div class="flex gap-2">
              <input class="input w-40 font-mono" placeholder="name" [value]="v.key" (input)="setVar(i, { key: val($event) })" aria-label="Variable name" />
              <input class="input flex-1" placeholder="value" [value]="v.value" (input)="setVar(i, { value: val($event) })" aria-label="Variable value" />
              <button type="button" class="text-sm underline" (click)="removeVar(i)">remove</button>
            </div>
          }
          <button type="button" class="btn py-1" (click)="addVar()">Add variable</button>
        </section>

        <section class="card grid grid-cols-2 gap-4">
          <label class="text-sm"><span class="mb-1 block font-medium">Start (IST, blank = when armed)</span>
            <input type="datetime-local" class="input" [value]="scheduledAt()" (input)="scheduledAt.set(val($event))" /></label>
          <label class="text-sm"><span class="mb-1 block font-medium">Sends per minute</span>
            <input class="input" inputmode="numeric" [value]="rate()" (input)="rate.set(val($event))" [placeholder]="channel() === 'push' ? '5000' : '600'" /></label>
          <label class="text-sm"><span class="mb-1 block font-medium">Holdout %</span>
            <input class="input" inputmode="decimal" [value]="holdout()" (input)="holdout.set(val($event))" placeholder="0" /></label>
          <label class="text-sm"><span class="mb-1 block font-medium">Budget cap (₹){{ needsCap() ? ' · required' : '' }}</span>
            <input class="input" inputmode="decimal" [value]="budget()" (input)="budget.set(val($event))" /></label>
          @if (channel() === 'push') {
            <label class="text-sm"><span class="mb-1 block font-medium">Push expires after (hours, max 48)</span>
              <input class="input" inputmode="numeric" [value]="ttlHours()" (input)="ttlHours.set(val($event))" placeholder="12" /></label>
          }
        </section>

        <section class="card space-y-3">
          <label class="text-sm"><input type="checkbox" [checked]="followUp()" (change)="followUp.set(checked($event))" />
            Follow up on {{ otherChannel() }} with people who did not click</label>
          @if (followUp()) {
            <div class="grid grid-cols-2 gap-4">
              <label class="text-sm"><span class="mb-1 block font-medium">{{ otherChannel() }} template</span>
                <select class="input" [value]="followUpTemplate()" (change)="followUpTemplate.set(val($event))">
                  <option value="" disabled>Pick a template</option>
                  @for (t of followUpTemplates.value() ?? []; track t.key) { <option [value]="t.key">{{ t.key }}</option> }
                </select></label>
              <label class="text-sm"><span class="mb-1 block font-medium">After (hours, 1–72)</span>
                <input class="input" inputmode="numeric" [value]="followUpHours()" (input)="followUpHours.set(val($event))" /></label>
            </div>
          }
        </section>

        @if (error()) {
          <p class="rounded bg-red-50 p-3 text-sm text-red-800" role="alert">{{ error() }}</p>
        }
        <button type="submit" class="btn-primary" [disabled]="busy() || !ready()">{{ id() ? 'Save (back to draft)' : 'Save draft' }}</button>
      </form>
    }
  `,
})
export default class CampaignComposerPage {
  readonly #api = inject(Api);
  readonly #router = inject(Router);

  /** Route parameter when editing. */
  readonly id = input<string>();

  readonly name = signal('');
  readonly channel = signal<'push' | 'whatsapp'>('push');
  readonly templateKey = signal('');
  readonly segmentId = signal('');
  readonly vars = signal<Var[]>([{ key: 'url', value: 'https://' }]);
  readonly scheduledAt = signal('');
  readonly rate = signal('');
  readonly holdout = signal('');
  readonly budget = signal('');
  readonly ttlHours = signal('');
  readonly followUp = signal(false);
  readonly followUpTemplate = signal('');
  readonly followUpHours = signal('6');
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);

  readonly otherChannel = computed(() => (this.channel() === 'push' ? 'whatsapp' : 'push'));
  readonly needsCap = computed(() => this.channel() === 'whatsapp' || (this.followUp() && this.otherChannel() === 'whatsapp'));

  readonly existing = httpResource<Campaign>(() => (this.id() ? `/api/campaigns/${encodeURIComponent(this.id()!)}` : undefined));
  readonly templates = httpResource<{ key: string }[]>(() => `/api/campaigns/templates?channel=${this.channel()}`);
  readonly followUpTemplates = httpResource<{ key: string }[]>(() =>
    this.followUp() ? `/api/campaigns/templates?channel=${this.otherChannel()}` : undefined,
  );
  readonly segments = httpResource<SegmentRow[]>(() => '/api/segments');

  readonly ready = computed(
    () =>
      this.name().trim().length > 0 &&
      !!this.templateKey() &&
      !!this.segmentId() &&
      (!this.needsCap() || Number(this.budget()) > 0) &&
      (!this.followUp() || !!this.followUpTemplate()),
  );

  #loaded = false;

  constructor() {
    effect(() => {
      if (this.#loaded || !this.existing.hasValue()) return;
      const c = this.existing.value();
      this.#loaded = true;
      untracked(() => {
        this.name.set(c.name);
        this.channel.set(c.channel);
        this.templateKey.set(c.templateKey);
        this.segmentId.set(c.segmentId ?? '');
        this.vars.set(Object.entries(c.vars ?? {}).map(([key, value]) => ({ key, value })));
        this.scheduledAt.set(isoToIstInput(c.scheduledAt));
        this.rate.set(String(c.sendRatePerMinute));
        this.holdout.set(String(c.holdoutPct ?? ''));
        this.budget.set(c.budgetCapPaise != null ? String(c.budgetCapPaise / 100) : '');
        this.ttlHours.set(c.ttlSeconds ? String(c.ttlSeconds / 3600) : '');
        this.followUp.set(!!c.followUpChannel);
        this.followUpTemplate.set(c.followUpTemplateKey ?? '');
        this.followUpHours.set(c.followUpAfterMinutes ? String(c.followUpAfterMinutes / 60) : '6');
      });
    });
  }

  val(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }

  checked(e: Event): boolean {
    return (e.target as HTMLInputElement).checked;
  }

  setChannel(c: string): void {
    this.channel.set(c === 'whatsapp' ? 'whatsapp' : 'push');
    this.templateKey.set('');
    this.followUpTemplate.set('');
  }

  addVar(): void {
    this.vars.update((v) => [...v, { key: '', value: '' }]);
  }

  removeVar(i: number): void {
    this.vars.update((v) => v.filter((_, j) => j !== i));
  }

  setVar(i: number, patch: Partial<Var>): void {
    this.vars.update((v) => v.map((x, j) => (j === i ? { ...x, ...patch } : x)));
  }

  async save(event: Event): Promise<void> {
    event.preventDefault();
    this.busy.set(true);
    this.error.set(null);
    const vars = Object.fromEntries(this.vars().filter((v) => v.key.trim()).map((v) => [v.key.trim(), v.value.trim()]));
    const num = (s: string) => (s.trim() === '' ? null : Number(s));
    const body = {
      name: this.name().trim(),
      channel: this.channel(),
      templateKey: this.templateKey(),
      segmentId: this.segmentId(),
      vars,
      scheduledAt: istInputToIso(this.scheduledAt()),
      sendRatePerMinute: num(this.rate()),
      holdoutPct: num(this.holdout()),
      budgetCapPaise: this.budget().trim() === '' ? null : Math.round(Number(this.budget()) * 100),
      ttlSeconds: this.channel() === 'push' && this.ttlHours().trim() ? Math.round(Number(this.ttlHours()) * 3600) : null,
      followUpChannel: this.followUp() ? this.otherChannel() : null,
      followUpTemplateKey: this.followUp() ? this.followUpTemplate() : null,
      followUpAfterMinutes: this.followUp() ? Math.round(Number(this.followUpHours()) * 60) : null,
    };
    try {
      const saved = this.id()
        ? await this.#api.patch<Campaign>(`/api/campaigns/${encodeURIComponent(this.id()!)}`, body)
        : await this.#api.post<Campaign>('/api/campaigns', body);
      await this.#router.navigate(['/campaigns', saved.id]);
    } catch (e) {
      this.error.set(problemDetail(e, 'Could not save the campaign.'));
    } finally {
      this.busy.set(false);
    }
  }
}
