import { httpResource } from '@angular/common/http';
import { Component, computed, DestroyRef, ElementRef, inject, signal, viewChild } from '@angular/core';
import { TokenService } from '../../core/auth/token.service';
import { Api } from '../../shared/api';
import { IstPipe, problemDetail } from '../../shared/format';
import { Halt, HALT_CHANNELS, haltConfirmWord, haltLabel, HaltScope } from './halt-rules';

/**
 * The kill switches, in the header of every page (06-admin-ui §Dashboard).
 * Shows every halt in force, polls every 30 s, and opens the halt dialog.
 * Halting: CAMPAIGN_SEND or CONFIG_ADMIN, a reason, and the scope name typed.
 * Releasing: CONFIG_ADMIN, with a reason.
 */
@Component({
  selector: 'app-halt-bar',
  imports: [IstPipe],
  template: `
    <div class="flex flex-wrap items-center gap-3">
      @for (h of halts(); track h.scope + h.selector) {
        <div class="flex items-center gap-2 rounded border border-red-300 bg-red-50 px-3 py-1.5 text-sm text-red-900" role="status">
          <span class="font-semibold">HALTED · {{ label(h) }}</span>
          <span class="text-xs text-red-700" [title]="h.reason">since {{ h.since | ist }}{{ h.byEmail ? ' by ' + h.byEmail : '' }}</span>
          @if (canRelease()) {
            @if (releasing() === key(h)) {
              <input class="input w-48 py-1" placeholder="Why release now?" [value]="releaseReason()"
                     (input)="releaseReason.set(value($event))" aria-label="Reason for releasing" />
              <button type="button" class="btn py-1" [disabled]="busy() || releaseReason().trim().length < 5" (click)="release(h)">Release</button>
              <button type="button" class="text-xs underline" (click)="releasing.set(null)">Cancel</button>
            } @else {
              <button type="button" class="text-xs font-medium underline" (click)="startRelease(h)">Release…</button>
            }
          }
        </div>
      }
      @if (canHalt()) {
        <button type="button" class="rounded border border-red-600 px-3 py-1.5 text-sm font-semibold text-red-700 hover:bg-red-50"
                (click)="open()">Halt…</button>
      }
      @if (error()) {
        <span class="text-sm text-red-700" role="alert">{{ error() }}</span>
      }
    </div>

    <dialog #dialog class="m-auto w-full max-w-md rounded-lg p-6 shadow-xl backdrop:bg-slate-900/40" aria-labelledby="halt-title">
      <h2 id="halt-title" class="mb-1 text-lg font-semibold text-red-800">Stop sending</h2>
      <p class="mb-4 text-sm text-slate-600">Takes effect on the next send, everywhere. Running and scheduled campaigns on this scope are paused.</p>
      <form (submit)="halt($event)" class="space-y-3">
        <label class="block text-sm">
          <span class="mb-1 block font-medium">What to stop</span>
          <select class="input" [value]="scope()" (change)="setScope(value($event))">
            <option value="marketing">All marketing (order updates keep going)</option>
            <option value="channel">One channel (everything on it, order updates too)</option>
            <option value="journey">One journey</option>
          </select>
        </label>
        @if (scope() === 'channel') {
          <label class="block text-sm">
            <span class="mb-1 block font-medium">Channel</span>
            <select class="input" [value]="selector()" (change)="selector.set(value($event))">
              @for (c of channels; track c) {
                <option [value]="c">{{ c }}</option>
              }
              <option value="*">every channel</option>
            </select>
          </label>
        }
        @if (scope() === 'journey') {
          <label class="block text-sm">
            <span class="mb-1 block font-medium">Journey key</span>
            <input class="input font-mono" placeholder="cart_abandon" [value]="selector()" (input)="selector.set(value($event))" />
          </label>
        }
        <label class="block text-sm">
          <span class="mb-1 block font-medium">Why</span>
          <textarea class="input" rows="2" [value]="reason()" (input)="reason.set(value($event))"
                    placeholder="Wrong price in the Diwali push"></textarea>
        </label>
        <label class="block text-sm">
          <span class="mb-1 block font-medium">Type <code class="rounded bg-slate-100 px-1">{{ confirmWord() }}</code> to confirm</span>
          <input class="input font-mono" autocomplete="off" [value]="typed()" (input)="typed.set(value($event))" />
        </label>
        @if (dialogError()) {
          <p class="rounded bg-red-50 p-2 text-sm text-red-800" role="alert">{{ dialogError() }}</p>
        }
        <div class="flex justify-end gap-2 pt-2">
          <button type="button" class="btn" (click)="close()">Cancel</button>
          <button type="submit"
                  class="inline-flex items-center rounded bg-red-700 px-4 py-2 text-sm font-semibold text-white hover:bg-red-800 disabled:opacity-50"
                  [disabled]="!ready() || busy()">Halt now</button>
        </div>
      </form>
    </dialog>
  `,
})
export class HaltBar {
  readonly #api = inject(Api);
  readonly #tokens = inject(TokenService);
  readonly dialog = viewChild.required<ElementRef<HTMLDialogElement>>('dialog');

  readonly channels = HALT_CHANNELS;
  readonly haltsResource = httpResource<Halt[]>(() => '/api/halt');
  readonly halts = computed(() => (this.haltsResource.hasValue() ? this.haltsResource.value() : []));

  readonly canHalt = computed(() => this.#tokens.has('CAMPAIGN_SEND') || this.#tokens.has('CONFIG_ADMIN'));
  readonly canRelease = computed(() => this.#tokens.has('CONFIG_ADMIN'));

  readonly scope = signal<HaltScope>('marketing');
  readonly selector = signal('*');
  readonly reason = signal('');
  readonly typed = signal('');
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);
  readonly dialogError = signal<string | null>(null);
  readonly releasing = signal<string | null>(null);
  readonly releaseReason = signal('');

  readonly confirmWord = computed(() => haltConfirmWord(this.scope(), this.selector()));
  readonly ready = computed(
    () =>
      this.reason().trim().length >= 5 &&
      this.typed().trim() === this.confirmWord() &&
      (this.scope() !== 'journey' || /^([a-z0-9_]{1,64}|\*)$/.test(this.selector())),
  );

  readonly label = haltLabel;

  constructor() {
    const timer = setInterval(() => this.haltsResource.reload(), 30_000);
    inject(DestroyRef).onDestroy(() => clearInterval(timer));
  }

  key(h: Halt): string {
    return `${h.scope}:${h.selector}`;
  }

  value(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }

  setScope(s: string): void {
    this.scope.set(s as HaltScope);
    this.selector.set(s === 'channel' ? 'whatsapp' : s === 'journey' ? '' : '*');
    this.typed.set('');
  }

  startRelease(h: Halt): void {
    this.releaseReason.set('');
    this.releasing.set(this.key(h));
  }

  open(): void {
    this.setScope('marketing');
    this.reason.set('');
    this.dialogError.set(null);
    this.dialog().nativeElement.showModal();
  }

  close(): void {
    this.dialog().nativeElement.close();
  }

  async halt(event: Event): Promise<void> {
    event.preventDefault();
    if (!this.ready()) return;
    this.busy.set(true);
    this.dialogError.set(null);
    try {
      await this.#api.post('/api/halt', { scope: this.scope(), selector: this.selector(), reason: this.reason().trim() });
      this.close();
      this.haltsResource.reload();
    } catch (e) {
      this.dialogError.set(problemDetail(e, 'Could not halt.'));
    } finally {
      this.busy.set(false);
    }
  }

  async release(h: Halt): Promise<void> {
    this.busy.set(true);
    this.error.set(null);
    try {
      const reason = encodeURIComponent(this.releaseReason().trim());
      await this.#api.delete(`/api/halt/${h.scope}/${encodeURIComponent(h.selector)}?reason=${reason}`);
      this.releasing.set(null);
      this.haltsResource.reload();
    } catch (e) {
      this.error.set(problemDetail(e, 'Could not release the halt.'));
    } finally {
      this.busy.set(false);
    }
  }
}
