import { HttpClient, httpResource } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { form, FormField, pattern, required, submit, validate } from '@angular/forms/signals';
import { firstValueFrom } from 'rxjs';
import { CopyVersion } from '../../core/api/models';
import { TokenService } from '../../core/auth/token.service';
import { IstPipe, problemDetail } from '../../shared/format';
import { EmptyState, ErrorState, LoadingState } from '../../shared/states';

const CHANNELS = ['whatsapp', 'push', 'email', 'sms', 'rcs'];
const SURFACES = ['cart', 'thank_you', 'soft_ask', 'wa_thread', 'checkout_notice'];

/**
 * Every wording a shopper can agree to, and how many grants rest on each.
 * Wordings are immutable: new words are a new version. Registering one needs
 * CONFIG_ADMIN and is audited.
 */
@Component({
  selector: 'app-consent-copy',
  imports: [FormField, IstPipe, LoadingState, ErrorState, EmptyState],
  template: `
    <header class="mb-6">
      <h1 class="text-2xl font-semibold">Consent copy</h1>
      <p class="text-sm text-slate-500">The exact words shoppers agree to. A consent counts only if its wording is registered here.</p>
    </header>

    @if (versions.isLoading() && !versions.hasValue()) {
      <app-loading />
    } @else if (versions.error()) {
      <app-error [error]="versions.error()" (retry)="versions.reload()" />
    } @else if (versions.hasValue()) {
      @if (versions.value().length === 0) {
        <app-empty text="No wording registered." />
      } @else {
        <table class="table mb-10">
          <thead><tr><th>Version</th><th>Channel</th><th>Where shown</th><th>Covers</th><th>Wording</th><th class="num">Grants</th><th>Registered</th></tr></thead>
          <tbody>
            @for (v of versions.value(); track v.version) {
              <tr>
                <td class="font-mono text-xs">{{ v.version }}</td>
                <td>{{ v.channel }}</td>
                <td>{{ v.surface }}</td>
                <td>{{ v.purposes.join(', ') }}</td>
                <td class="max-w-md">{{ v.text }}</td>
                <td class="num">{{ v.grants }}</td>
                <td>{{ v.createdAt | ist }}</td>
              </tr>
            }
          </tbody>
        </table>
      }
    }

    @if (canRegister()) {
      <section class="max-w-2xl rounded-lg border border-slate-200 bg-white p-6">
        <h2 class="mb-1 font-semibold">Register a new wording</h2>
        <p class="mb-4 text-sm text-slate-500">Copy the words exactly as the shopper will see them. You cannot edit them later.</p>
        @if (saveError()) {
          <p class="mb-4 rounded bg-red-50 p-3 text-sm text-red-800" role="alert">{{ saveError() }}</p>
        }
        @if (saved()) {
          <p class="mb-4 rounded bg-emerald-50 p-3 text-sm text-emerald-800" role="status">Registered {{ saved() }}.</p>
        }
        <form (submit)="register($event)" novalidate class="grid grid-cols-2 gap-4">
          <label class="text-sm">
            <span class="mb-1 block font-medium">Version</span>
            <input placeholder="wa_cart_v2" [formField]="copyForm.version" class="input font-mono" />
          </label>
          <label class="text-sm">
            <span class="mb-1 block font-medium">Channel</span>
            <select [formField]="copyForm.channel" class="input">
              @for (c of channels; track c) { <option [value]="c">{{ c }}</option> }
            </select>
          </label>
          <label class="text-sm">
            <span class="mb-1 block font-medium">Where it is shown</span>
            <select [formField]="copyForm.surface" class="input">
              @for (s of surfaces; track s) { <option [value]="s">{{ s }}</option> }
            </select>
          </label>
          <fieldset class="text-sm">
            <legend class="mb-1 font-medium">Covers</legend>
            <label class="mr-4"><input type="checkbox" [formField]="copyForm.transactional" /> order updates</label>
            <label><input type="checkbox" [formField]="copyForm.marketing" /> offers</label>
          </fieldset>
          <label class="col-span-2 text-sm">
            <span class="mb-1 block font-medium">Exact wording</span>
            <textarea rows="3" [formField]="copyForm.text" class="input"></textarea>
          </label>
          @for (e of formErrors(); track e) {
            <p class="col-span-2 text-sm text-red-700">{{ e }}</p>
          }
          <div class="col-span-2">
            <button type="submit" class="btn-primary" [disabled]="busy()">Register</button>
          </div>
        </form>
      </section>
    }
  `,
})
export default class ConsentCopyPage {
  readonly #http = inject(HttpClient);
  readonly #tokens = inject(TokenService);

  readonly channels = CHANNELS;
  readonly surfaces = SURFACES;
  readonly versions = httpResource<CopyVersion[]>(() => '/api/consent-copy');
  readonly canRegister = computed(() => this.#tokens.has('CONFIG_ADMIN'));

  readonly busy = signal(false);
  readonly saved = signal<string | null>(null);
  readonly saveError = signal<string | null>(null);

  readonly draft = signal({
    version: '',
    channel: 'whatsapp',
    surface: 'cart',
    text: '',
    transactional: true,
    marketing: false,
  });
  readonly copyForm = form(this.draft, (p) => {
    required(p.version, { message: 'Give it a version name' });
    pattern(p.version, /^[a-z][a-z0-9_]{1,40}_v\d{1,3}$/, { message: 'Version looks like wa_cart_v2' });
    required(p.text, { message: 'Enter the exact wording' });
    validate(p.marketing, ({ value, valueOf }) =>
      value() || valueOf(p.transactional) ? null : { kind: 'purpose', message: 'Pick what it covers' },
    );
  });

  readonly formErrors = computed(() => {
    const f = this.copyForm;
    return [f.version, f.text, f.marketing]
      .filter((x) => x().touched())
      .flatMap((x) => x().errors().map((e) => e.message ?? e.kind));
  });

  async register(event: Event): Promise<void> {
    event.preventDefault();
    await submit(this.copyForm, async () => {
      this.busy.set(true);
      this.saved.set(null);
      this.saveError.set(null);
      const d = this.draft();
      try {
        await firstValueFrom(
          this.#http.post('/api/consent-copy', {
            version: d.version,
            channel: d.channel,
            surface: d.surface,
            text: d.text,
            purposes: [d.transactional && 'transactional', d.marketing && 'marketing'].filter(Boolean),
          }),
        );
        this.saved.set(d.version);
        this.draft.update((x) => ({ ...x, version: '', text: '' }));
        this.versions.reload();
      } catch (e) {
        this.saveError.set(problemDetail(e, 'Could not register the wording.'));
      } finally {
        this.busy.set(false);
      }
    });
  }
}
