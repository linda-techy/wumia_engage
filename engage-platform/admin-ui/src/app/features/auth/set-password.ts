import { Component, inject, input, signal } from '@angular/core';
import { form, FormField, minLength, required, submit, validate } from '@angular/forms/signals';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';
import { problemDetail } from '../../shared/format';

/** The one-time link from an invite or the bootstrap owner: ?token=… */
@Component({
  selector: 'app-set-password',
  imports: [FormField, RouterLink],
  template: `
    <div class="flex min-h-screen items-center justify-center bg-slate-50 p-4">
      <div class="w-full max-w-sm rounded-lg border border-slate-200 bg-white p-8 shadow-sm">
        <h1 class="mb-1 text-xl font-semibold">Set your password</h1>
        <p class="mb-6 text-sm text-slate-500">At least 12 characters. This link works once.</p>
        @if (done()) {
          <p class="mb-4 rounded bg-emerald-50 p-3 text-sm text-emerald-800" role="status">Password set.</p>
          <a routerLink="/login" class="btn-primary block w-full text-center">Sign in</a>
        } @else if (!token()) {
          <p class="rounded bg-red-50 p-3 text-sm text-red-800" role="alert">This link is incomplete. Open the full link again.</p>
        } @else {
          @if (error()) {
            <p class="mb-4 rounded bg-red-50 p-3 text-sm text-red-800" role="alert">{{ error() }}</p>
          }
          <form (submit)="save($event)" novalidate class="space-y-4">
            <label class="block text-sm">
              <span class="mb-1 block font-medium">New password</span>
              <input type="password" autocomplete="new-password" [formField]="pwForm.password" class="input" />
            </label>
            <label class="block text-sm">
              <span class="mb-1 block font-medium">Type it again</span>
              <input type="password" autocomplete="new-password" [formField]="pwForm.repeat" class="input" />
            </label>
            @for (e of pwForm.repeat().errors(); track e.kind) {
              @if (pwForm.repeat().touched()) {
                <p class="text-sm text-red-700">{{ e.message }}</p>
              }
            }
            <button type="submit" class="btn-primary w-full" [disabled]="busy()">Set password</button>
          </form>
        }
      </div>
    </div>
  `,
})
export default class SetPassword {
  readonly #auth = inject(AuthService);

  readonly token = input<string>();
  readonly busy = signal(false);
  readonly done = signal(false);
  readonly error = signal<string | null>(null);

  readonly pw = signal({ password: '', repeat: '' });
  readonly pwForm = form(this.pw, (p) => {
    required(p.password);
    minLength(p.password, 12, { message: 'At least 12 characters' });
    validate(p.repeat, ({ value, valueOf }) =>
      value() === valueOf(p.password) ? null : { kind: 'mismatch', message: 'The two passwords differ' },
    );
  });

  async save(event: Event): Promise<void> {
    event.preventDefault();
    await submit(this.pwForm, async () => {
      this.busy.set(true);
      this.error.set(null);
      try {
        await this.#auth.setPassword(this.token() ?? '', this.pw().password);
        this.done.set(true);
      } catch (e) {
        this.error.set(problemDetail(e, 'Could not set the password.'));
      } finally {
        this.busy.set(false);
      }
    });
  }
}
