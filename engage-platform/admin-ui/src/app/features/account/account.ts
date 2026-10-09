import { Component, computed, inject, signal } from '@angular/core';
import { AuthService } from '../../core/auth/auth.service';
import { TokenService } from '../../core/auth/token.service';
import { problemDetail } from '../../shared/format';

/** Your account: roles, and a new set of recovery codes (needs your authenticator). */
@Component({
  selector: 'app-account',
  template: `
    <header class="mb-6">
      <h1 class="text-2xl font-semibold">Account</h1>
      <p class="text-sm text-slate-500">{{ operator()?.fullName }} · {{ operator()?.email }} · {{ roles() }}</p>
    </header>

    <section class="card max-w-xl">
      <h2 class="mb-1 font-semibold">Recovery codes</h2>
      <p class="mb-4 text-sm text-slate-600">Ten single-use codes that sign you in if you lose your phone. Getting a new set makes the old ones stop working.</p>
      @if (codes().length) {
        <p class="mb-2 text-sm font-medium">Save these now; they are shown only once.</p>
        <ul class="mb-3 grid grid-cols-2 gap-2 rounded bg-slate-50 p-3 font-mono text-sm" aria-label="Recovery codes">
          @for (c of codes(); track c) { <li>{{ c }}</li> }
        </ul>
        <button type="button" class="btn" (click)="copy()">{{ copied() ? 'Copied' : 'Copy all' }}</button>
      } @else {
        <form class="flex items-end gap-3" (submit)="reissue($event)">
          <label class="text-sm"><span class="mb-1 block font-medium">Current code from your authenticator</span>
            <input class="input w-40 tracking-widest" inputmode="numeric" autocomplete="one-time-code" [value]="code()" (input)="code.set(val($event))" /></label>
          <button type="submit" class="btn-primary" [disabled]="busy() || !codeOk()">Get new codes</button>
        </form>
      }
      @if (error()) { <p class="mt-3 rounded bg-red-50 p-3 text-sm text-red-800" role="alert">{{ error() }}</p> }
    </section>
  `,
})
export default class AccountPage {
  readonly #auth = inject(AuthService);
  readonly #tokens = inject(TokenService);

  readonly operator = this.#tokens.operator;
  readonly roles = computed(() => this.#tokens.roles().join(', '));
  readonly code = signal('');
  readonly codeOk = computed(() => /^\d{6}$/.test(this.code().trim()));
  readonly codes = signal<string[]>([]);
  readonly copied = signal(false);
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);

  val(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }

  async reissue(event: Event): Promise<void> {
    event.preventDefault();
    this.busy.set(true);
    this.error.set(null);
    try {
      this.codes.set(await this.#auth.reissueRecoveryCodes(this.code()));
      this.code.set('');
    } catch (e) {
      this.error.set(problemDetail(e, 'Could not issue new codes.'));
    } finally {
      this.busy.set(false);
    }
  }

  async copy(): Promise<void> {
    await navigator.clipboard.writeText(this.codes().join('\n'));
    this.copied.set(true);
  }
}
