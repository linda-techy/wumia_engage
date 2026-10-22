import { httpResource } from '@angular/common/http';
import { Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { Api, istInputToIso } from '../../shared/api';
import { problemDetail } from '../../shared/format';
import { ErrorState, LoadingState } from '../../shared/states';
import { SegmentRow } from '../segments/segments';
import { Campaign, DISCOUNT_CODE, discountLink, discountPath, parseDiscountLink } from './campaign-rules';
import { PosterPicker } from './poster-picker';
import { BODY_MAX, CUSTOM_PUSH_TEMPLATE, PushPreview, TITLE_MAX, length } from './push-preview';

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
  imports: [RouterLink, LoadingState, ErrorState, PosterPicker, PushPreview],
  template: `
    <a [routerLink]="id() ? ['/campaigns', id()] : ['/campaigns']" class="text-sm text-slate-600 underline">← Back</a>
    <header class="mb-6 mt-3">
      <h1 class="text-2xl font-semibold">{{ id() ? 'Edit campaign' : 'New campaign' }}</h1>
      <p class="text-sm text-slate-500">Pick a template. With “Your own title and text” you write the notification yourself, with a poster.</p>
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
            <select class="input" [value]="templateKey()" (change)="pickTemplate(val($event))">
              <option value="" disabled>Pick a template</option>
              @for (t of templates.value() ?? []; track t.key) { <option [value]="t.key">{{ t.key === customKey ? 'Your own title and text (poster)' : t.key }}{{ t.headerImage ? ' · poster' : '' }}</option> }
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

        @if (channel() === 'push') {
          @if (custom()) {
            <section class="card space-y-3" aria-labelledby="copy-h">
              <h2 id="copy-h" class="text-sm font-semibold">Notification text</h2>
              <label class="block text-sm"><span class="mb-1 flex justify-between font-medium">Title
                <span class="font-normal" [class.text-red-700]="titleLength() > titleMax">{{ titleLength() }}/{{ titleMax }}</span></span>
                <input class="input" [value]="named('title')" (input)="setNamed('title', val($event))"
                       placeholder="Wedding season sparkle 💍" /></label>
              <label class="block text-sm"><span class="mb-1 flex justify-between font-medium">Text
                <span class="font-normal" [class.text-red-700]="bodyLength() > bodyMax">{{ bodyLength() }}/{{ bodyMax }}</span></span>
                <textarea class="input h-20" [value]="named('body')" (input)="setNamed('body', val($event))"
                          placeholder="Gold huggies for every wedding look. Anti-tarnish, under ₹2,000."></textarea></label>
              <p class="text-xs text-slate-500">English only. Past the limit the text is cut with "…". An emoji counts as one character.</p>
            </section>
          }
          <app-poster-picker [value]="image()" (changed)="setImage($event)" />
          <section class="card space-y-3" aria-labelledby="timer-h">
            <h2 id="timer-h" class="text-sm font-semibold">Offer timer</h2>
            <label class="text-sm"><input type="checkbox" [checked]="timer()" (change)="setTimer(checked($event))" />
              This offer ends at a set time (a countdown, like Myntra's sale timers)</label>
            @if (timer()) {
              <div class="grid grid-cols-2 gap-4">
                <label class="text-sm"><span class="mb-1 block font-medium">Offer ends (IST)</span>
                  <input type="datetime-local" class="input" [value]="endsAt()" (input)="endsAt.set(val($event))" /></label>
                <label class="text-sm"><span class="mb-1 flex justify-between font-medium">Offer name
                  <span class="font-normal" [class.text-red-700]="named('offer').length > 24">{{ named('offer').length }}/24</span></span>
                  <input class="input" [value]="named('offer')" (input)="setNamed('offer', val($event))" placeholder="Diwali Festive Edit" /></label>
              </div>
              <p class="text-xs text-slate-500">Write <code class="rounded bg-slate-100 px-1">&#123;time_left&#125;</code> in the title or text, e.g.
                "Dhanteras offer ends in &#123;time_left&#125;", and each push says how long is left when it goes. Nothing is delivered after the end.</p>
              <label class="block text-sm"><input type="checkbox" [checked]="countdownPoster()" (change)="countdownPoster.set(checked($event))" />
                Countdown on the poster: an "OFFER ENDS IN 02:14:33" band, showing the time left when the phone displays it</label>
              <label class="block text-sm"><input type="checkbox" [checked]="countdownReminders()" (change)="setReminders(checked($event))" />
                "Last chance" pushes at 24 h, 3 h and 1 h before the end, to people who have not tapped yet (each replaces the previous one)</label>
              <p class="text-xs text-slate-500">A future Wumika app gets the same campaign and can show a live ticking timer from the same end time.</p>
            }
          </section>
          <section class="card">
            <app-push-preview [title]="custom() ? named('title') : 'Title from ' + (templateKey() || 'the template')"
                              [body]="custom() ? named('body') : 'Text from the template, with your variables'"
                              [image]="image()" [url]="named('url') || null"
                              [countdownMs]="countdownMs()" [countdownPoster]="timer() && countdownPoster()" />
          </section>
        }

        @if (channel() === 'whatsapp' && waPoster()) {
          <app-poster-picker [value]="image()" (changed)="setImage($event)" />
          <p class="-mt-4 text-xs text-slate-500">This WhatsApp template shows the poster above the message.</p>
        }

        <section class="card space-y-2" aria-labelledby="discount-h">
          <h2 id="discount-h" class="text-sm font-semibold">Discount code (applies itself)</h2>
          <input class="input w-60 font-mono uppercase" [value]="discountCode()" (input)="discountCode.set(val($event).trim())"
                 placeholder="DHANTERAS15" aria-label="Discount code" />
          <p class="text-xs text-slate-500">Create the code in Shopify (Discounts) first. The link then applies it by itself, so nobody has
            to copy it. Leave blank for no code.</p>
          @if (discountCode() && !discountValid()) {
            <p class="text-xs text-red-700">Letters, digits, - and _ only, 3 to 40 characters.</p>
          } @else if (discountCode() && finalLink()) {
            <p class="break-all text-xs text-slate-600">Link: {{ finalLink() }}</p>
          }
        </section>

        <section class="card space-y-2">
          <h2 class="text-sm font-semibold">Template variables</h2>
          <p class="text-xs text-slate-500">{{ channel() === 'whatsapp'
            ? 'The template needs every value below. link_path is the page after https://wumika.com/, e.g. collections/diwali-gifts.'
            : 'For example collection, festival, url (https://).' }}</p>
          @for (v of vars(); track $index; let i = $index) {
            @if (!managed(v.key)) {
            <div class="flex gap-2">
              <input class="input w-40 font-mono" placeholder="name" [value]="v.key" (input)="setVar(i, { key: val($event) })" aria-label="Variable name" />
              <input class="input flex-1" placeholder="value" [value]="v.value" (input)="setVar(i, { value: val($event) })" aria-label="Variable value" />
              <button type="button" class="text-sm underline" (click)="removeVar(i)">remove</button>
            </div>
            }
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

        <section class="card space-y-3" [hidden]="timer() && countdownReminders()">
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
  /** The poster, kept as the template variable {@code image}. */
  readonly image = computed(() => this.vars().find((v) => v.key === 'image')?.value || null);

  readonly titleMax = TITLE_MAX;
  readonly bodyMax = BODY_MAX;
  /** The template whose title and text are written here. */
  readonly customKey = CUSTOM_PUSH_TEMPLATE;
  readonly custom = computed(() => this.channel() === 'push' && this.templateKey() === CUSTOM_PUSH_TEMPLATE);
  readonly titleLength = computed(() => length(this.named('title')));
  readonly bodyLength = computed(() => length(this.named('body')));

  named(key: string): string {
    return this.vars().find((v) => v.key === key)?.value ?? '';
  }

  setNamed(key: string, value: string): void {
    this.vars.update((vs) => (vs.some((v) => v.key === key)
      ? vs.map((v) => (v.key === key ? { ...v, value } : v))
      : [...vs, { key, value }]));
  }

  /** Variables edited by their own fields above, hidden from the generic list. */
  managed(key: string): boolean {
    return key === 'image' || (this.custom() && (key === 'title' || key === 'body')) || (this.timer() && key === 'offer');
  }

  setImage(url: string | null): void {
    this.vars.update((vs) => {
      const rest = vs.filter((v) => v.key !== 'image');
      return url ? [...rest, { key: 'image', value: url }] : rest;
    });
  }
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

  /** Shopify auto-apply code; the link variable carries it (url for push, link_path for WhatsApp). */
  readonly discountCode = signal('');
  readonly discountValid = computed(() => DISCOUNT_CODE.test(this.discountCode()));
  /** The variable that holds the click-through: url (push) or the WhatsApp button's suffix. */
  readonly linkVar = computed(() => (this.channel() === 'push' ? 'url' : 'link_path'));
  readonly finalLink = computed(() => {
    const raw = this.named(this.linkVar()).trim();
    if (!raw || !this.discountValid()) return null;
    try {
      return this.channel() === 'push' ? discountLink(raw, this.discountCode()) : 'https://wumika.com/' + discountPath(raw, this.discountCode());
    } catch {
      return null;
    }
  });

  /** Offer countdown (V44). */
  readonly timer = signal(false);
  readonly endsAt = signal('');
  readonly countdownPoster = signal(true);
  readonly countdownReminders = signal(true);
  /** Time left at the campaign's start (or now), for the preview's {time_left} and poster band. */
  readonly countdownMs = computed(() => {
    const end = this.timer() ? istInputToIso(this.endsAt()) : null;
    if (!end) return null;
    const start = istInputToIso(this.scheduledAt());
    return Math.max(0, new Date(end).getTime() - (start ? new Date(start).getTime() : Date.now()));
  });

  setTimer(on: boolean): void {
    this.timer.set(on);
    if (on && !this.named('offer')) this.setNamed('offer', this.name().trim().slice(0, 24));
  }

  setReminders(on: boolean): void {
    this.countdownReminders.set(on);
    if (on) this.followUp.set(false);              // the reminders are the follow-up
  }

  readonly otherChannel = computed(() => (this.channel() === 'push' ? 'whatsapp' : 'push'));
  readonly needsCap = computed(() => this.channel() === 'whatsapp' || (this.followUp() && this.otherChannel() === 'whatsapp'));

  readonly existing = httpResource<Campaign>(() => (this.id() ? `/api/campaigns/${encodeURIComponent(this.id()!)}` : undefined));
  readonly templates = httpResource<{ key: string; vars?: string; headerImage?: boolean }[]>(
    () => `/api/campaigns/templates?channel=${this.channel()}`);
  readonly selected = computed(() => (this.templates.value() ?? []).find((t) => t.key === this.templateKey()));
  /** A WhatsApp campaign template with the poster as its header. */
  readonly waPoster = computed(() => !!this.selected()?.headerImage);

  /** Picks a template and adds a row for every value it needs that is not there yet. */
  pickTemplate(key: string): void {
    this.templateKey.set(key);
    const t = (this.templates.value() ?? []).find((x) => x.key === key);
    const needed = (t?.vars ?? '').split(',').map((v) => v.trim()).filter(Boolean)
      .filter((v) => v !== 'image' && !(key === CUSTOM_PUSH_TEMPLATE && (v === 'title' || v === 'body')))
      .filter((v) => !(this.discountCode() && v === 'code'));
    this.vars.update((vs) => [...vs, ...needed.filter((v) => !vs.some((x) => x.key === v)).map((key) => ({ key, value: '' }))]);
  }
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
      (!this.followUp() || !!this.followUpTemplate()) &&
      (!this.custom() || (this.named('title').trim().length > 0 && this.named('body').trim().length > 0)) &&
      (!this.waPoster() || !!this.image()) &&
      (!this.timer() || (!!this.endsAt() && this.named('offer').trim().length > 0 && this.named('offer').length <= 24)),
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
        // A saved discount link is shown as the page plus the code, so it can be edited.
        const linkKey = c.channel === 'push' ? 'url' : 'link_path';
        const saved = parseDiscountLink((c.vars ?? {})[linkKey] ?? '');
        this.discountCode.set(saved?.code ?? (c.vars ?? {})['code'] ?? '');
        this.vars.set(Object.entries(c.vars ?? {}).map(([key, value]) => {
          if (key !== linkKey || !saved) return { key, value };
          return { key, value: c.channel === 'push' ? new URL(saved.target, value).href : saved.target.replace(/^\//, '') };
        }));
        this.scheduledAt.set(isoToIstInput(c.scheduledAt));
        this.rate.set(String(c.sendRatePerMinute));
        this.holdout.set(String(c.holdoutPct ?? ''));
        this.budget.set(c.budgetCapPaise != null ? String(c.budgetCapPaise / 100) : '');
        this.ttlHours.set(c.ttlSeconds ? String(c.ttlSeconds / 3600) : '');
        this.followUp.set(!!c.followUpChannel);
        this.followUpTemplate.set(c.followUpTemplateKey ?? '');
        this.followUpHours.set(c.followUpAfterMinutes ? String(c.followUpAfterMinutes / 60) : '6');
        this.timer.set(!!c.endsAt);
        this.endsAt.set(isoToIstInput(c.endsAt ?? null));
        this.countdownPoster.set(!!c.countdownPoster);
        this.countdownReminders.set(!!c.countdownReminders);
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
    const timer = this.channel() === 'push' && this.timer();
    const vars: Record<string, string> = Object.fromEntries(this.vars().filter((v) => v.key.trim() && (timer || v.key.trim() !== 'offer'))
      .map((v) => [v.key.trim(), v.value.trim()]));
    if (this.discountCode()) {
      if (!this.discountValid()) {
        this.error.set('The discount code has letters, digits, - and _ only.');
        this.busy.set(false);
        return;
      }
      const link = this.finalLink();
      if (link) vars[this.linkVar()] = this.channel() === 'push' ? link : link.replace('https://wumika.com/', '');
      vars['code'] = this.discountCode();
    }
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
      endsAt: timer ? istInputToIso(this.endsAt()) : null,
      countdownPoster: timer && this.countdownPoster() && !!this.image(),
      countdownReminders: timer && this.countdownReminders(),
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
