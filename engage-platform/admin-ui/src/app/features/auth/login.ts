import { HttpErrorResponse } from '@angular/common/http';
import { Component, inject, input, signal } from '@angular/core';
import { email, form, FormField, maxLength, minLength, pattern, required, submit } from '@angular/forms/signals';
import { Router } from '@angular/router';
import QRCode from 'qrcode';
import { Problem } from '../../core/api/models';
import { AuthService } from '../../core/auth/auth.service';
import { problemDetail } from '../../shared/format';

type Step = 'credentials' | 'mfa' | 'recovery' | 'enrol' | 'codes';

/**
 * Sign-in in up to three steps. Password first; then the authenticator code
 * when MFA is set up; or, for a role that must use MFA but has not set it up
 * (a new OWNER), enrolment with a QR code, then sign in again. Confirming
 * enrolment shows ten recovery codes once; any one can stand in for the
 * authenticator at sign-in.
 */
@Component({
  selector: 'app-login',
  imports: [FormField],
  template: `
    <div class="flex min-h-screen items-center justify-center bg-slate-50 p-4">
      <div class="w-full max-w-sm rounded-lg border border-slate-200 bg-white p-8 shadow-sm">
        <h1 class="mb-1 text-xl font-semibold">WUMIKA Engage</h1>
        <p class="mb-6 text-sm text-slate-500">{{ subtitle() }}</p>

        @if (notice()) {
          <p class="mb-4 rounded bg-emerald-50 p-3 text-sm text-emerald-800" role="status">{{ notice() }}</p>
        }
        @if (error()) {
          <p class="mb-4 rounded bg-red-50 p-3 text-sm text-red-800" role="alert">{{ error() }}</p>
        }

        @switch (step()) {
          @case ('credentials') {
            <form (submit)="signIn($event)" novalidate class="space-y-4">
              <label class="block text-sm">
                <span class="mb-1 block font-medium">Email</span>
                <input type="email" autocomplete="username" [formField]="credsForm.email" class="input" />
              </label>
              <label class="block text-sm">
                <span class="mb-1 block font-medium">Password</span>
                <input type="password" autocomplete="current-password" [formField]="credsForm.password" class="input" />
              </label>
              <button type="submit" class="btn-primary w-full" [disabled]="busy()">Sign in</button>
            </form>
          }
          @case ('mfa') {
            <form (submit)="verify($event)" novalidate class="space-y-4">
              <label class="block text-sm">
                <span class="mb-1 block font-medium">Code from your authenticator app</span>
                <input inputmode="numeric" autocomplete="one-time-code" [formField]="codeForm.code" class="input tracking-widest" />
              </label>
              <button type="submit" class="btn-primary w-full" [disabled]="busy()">Verify</button>
              <button type="button" class="w-full text-sm text-slate-600 underline" (click)="useRecovery()">Lost your phone? Use a recovery code</button>
              <button type="button" class="w-full text-sm text-slate-600 underline" (click)="restart()">Start again</button>
            </form>
          }
          @case ('recovery') {
            <form (submit)="verifyRecovery($event)" novalidate class="space-y-4">
              <label class="block text-sm">
                <span class="mb-1 block font-medium">One of your recovery codes</span>
                <input autocomplete="off" placeholder="XXXXX-XXXXX" [formField]="recoveryForm.code" class="input font-mono tracking-widest" />
              </label>
              <p class="text-xs text-slate-500">Each code works once. Afterwards, set up your authenticator again and get new codes.</p>
              <button type="submit" class="btn-primary w-full" [disabled]="busy()">Sign in</button>
              <button type="button" class="w-full text-sm text-slate-600 underline" (click)="restart()">Start again</button>
            </form>
          }
          @case ('codes') {
            <div class="space-y-4">
              <p class="text-sm">Two-step sign-in is on. Save these recovery codes somewhere safe, away from your phone. Each one signs you in once if you lose it. <strong>They are shown only now.</strong></p>
              <ul class="grid grid-cols-2 gap-2 rounded bg-slate-50 p-3 font-mono text-sm" aria-label="Recovery codes">
                @for (c of recoveryCodes(); track c) {
                  <li>{{ c }}</li>
                }
              </ul>
              <button type="button" class="btn w-full" (click)="copyCodes()">{{ copied() ? 'Copied' : 'Copy all' }}</button>
              <button type="button" class="btn-primary w-full" (click)="codesSaved()">I have saved them</button>
            </div>
          }
          @case ('enrol') {
            <div class="space-y-4">
              <p class="text-sm">Your role needs two-step sign-in. Scan this with Google Authenticator, Microsoft Authenticator or 1Password:</p>
              @if (qr()) {
                <img [src]="qr()" alt="QR code for your authenticator app" class="mx-auto h-48 w-48" />
              }
              @if (manualKey()) {
                <p class="text-xs text-slate-500">
                  Or enter this key by hand: <code class="break-all font-mono text-slate-800">{{ manualKey() }}</code>
                </p>
              }
              <form (submit)="confirmEnrolment($event)" novalidate class="space-y-4">
                <label class="block text-sm">
                  <span class="mb-1 block font-medium">The 6-digit code it shows</span>
                  <input inputmode="numeric" autocomplete="one-time-code" [formField]="codeForm.code" class="input tracking-widest" />
                </label>
                <button type="submit" class="btn-primary w-full" [disabled]="busy()">Turn on two-step sign-in</button>
              </form>
            </div>
          }
        }
      </div>
    </div>
  `,
})
export default class Login {
  readonly #auth = inject(AuthService);
  readonly #router = inject(Router);

  /** Where to go after signing in (the guard passes it). */
  readonly returnUrl = input<string>();

  readonly step = signal<Step>('credentials');
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);
  readonly notice = signal<string | null>(null);
  readonly qr = signal<string | null>(null);
  readonly manualKey = signal<string | null>(null);
  readonly recoveryCodes = signal<string[]>([]);
  readonly copied = signal(false);

  #mfaToken = '';
  #enrolToken = '';

  readonly creds = signal({ email: '', password: '' });
  readonly credsForm = form(this.creds, (p) => {
    required(p.email, { message: 'Enter your email' });
    email(p.email, { message: 'Enter a valid email' });
    required(p.password, { message: 'Enter your password' });
  });

  readonly code = signal({ code: '' });
  readonly codeForm = form(this.code, (p) => {
    required(p.code);
    minLength(p.code, 6);
    maxLength(p.code, 6);
    pattern(p.code, /^\d{6}$/);
  });

  readonly recovery = signal({ code: '' });
  readonly recoveryForm = form(this.recovery, (p) => {
    required(p.code, { message: 'Enter a recovery code' });
    pattern(p.code, /^[0-9A-Za-z][0-9A-Za-z \-]{8,12}[0-9A-Za-z]$/, { message: 'A code looks like XXXXX-XXXXX' });
  });

  subtitle(): string {
    switch (this.step()) {
      case 'credentials':
        return 'Sign in to the console';
      case 'mfa':
      case 'recovery':
        return 'Two-step sign-in';
      case 'codes':
        return 'Your recovery codes';
      default:
        return 'Set up two-step sign-in';
    }
  }

  async signIn(event: Event): Promise<void> {
    event.preventDefault();
    await submit(this.credsForm, async () => {
      await this.#run(async () => {
        const { email, password } = this.creds();
        try {
          const result = await this.#auth.login(email, password);
          if ('mfaRequired' in result) {
            this.#mfaToken = result.mfaToken;
            this.#goTo('mfa');
          } else {
            await this.#done();
          }
        } catch (e) {
          const problem = (e as HttpErrorResponse).error as Problem | undefined;
          if ((e as HttpErrorResponse).status === 403 && problem?.enrolToken) {
            this.#enrolToken = problem.enrolToken;
            await this.#startEnrolment();
            return;
          }
          throw e;
        }
      });
    });
  }

  async verify(event: Event): Promise<void> {
    event.preventDefault();
    await submit(this.codeForm, async () => {
      await this.#run(async () => {
        await this.#auth.mfa(this.#mfaToken, this.code().code);
        await this.#done();
      });
    });
  }

  async confirmEnrolment(event: Event): Promise<void> {
    event.preventDefault();
    await submit(this.codeForm, async () => {
      await this.#run(async () => {
        const codes = await this.#auth.confirmEnrolment(this.code().code, this.#enrolToken);
        this.#enrolToken = '';
        this.creds.update((c) => ({ ...c, password: '' }));
        this.qr.set(null);
        this.manualKey.set(null);
        this.recoveryCodes.set(codes);
        this.#goTo(codes.length ? 'codes' : 'credentials');
      });
    });
  }

  async verifyRecovery(event: Event): Promise<void> {
    event.preventDefault();
    await submit(this.recoveryForm, async () => {
      await this.#run(async () => {
        await this.#auth.mfa(this.#mfaToken, this.recovery().code);
        await this.#done();
      });
    });
  }

  useRecovery(): void {
    this.recovery.set({ code: '' });
    this.#goTo('recovery');
  }

  async copyCodes(): Promise<void> {
    await navigator.clipboard.writeText(this.recoveryCodes().join('\n'));
    this.copied.set(true);
  }

  codesSaved(): void {
    this.recoveryCodes.set([]);
    this.copied.set(false);
    this.#goTo('credentials');
    this.notice.set('Two-step sign-in is on. Sign in with your password and the next code.');
  }

  restart(): void {
    this.#mfaToken = '';
    this.#goTo('credentials');
  }

  async #startEnrolment(): Promise<void> {
    const enrolment = await this.#auth.startEnrolment(this.#enrolToken);
    this.qr.set(await QRCode.toDataURL(enrolment.provisioningUri, { margin: 1, width: 192 }));
    this.manualKey.set(new URL(enrolment.provisioningUri).searchParams.get('secret'));
    this.#goTo('enrol');
  }

  #goTo(step: Step): void {
    this.error.set(null);
    this.notice.set(null);
    this.code.set({ code: '' });
    this.step.set(step);
  }

  async #done(): Promise<void> {
    const target = this.returnUrl();
    await this.#router.navigateByUrl(target && target.startsWith('/') && !target.startsWith('//') ? target : '/');
  }

  async #run(action: () => Promise<void>): Promise<void> {
    this.busy.set(true);
    this.error.set(null);
    try {
      await action();
    } catch (e) {
      this.error.set(problemDetail(e, 'Sign-in failed. Try again.'));
    } finally {
      this.busy.set(false);
    }
  }
}
