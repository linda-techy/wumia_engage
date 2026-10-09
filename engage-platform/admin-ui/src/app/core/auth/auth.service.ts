import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { firstValueFrom, Observable, of } from 'rxjs';
import { catchError, finalize, map, shareReplay, switchMap, tap } from 'rxjs/operators';
import { Enrolment, MfaChallenge, Operator, SignedIn } from '../api/models';
import { TokenService } from './token.service';

/** Calls /api/auth. The refresh token travels only in its HttpOnly cookie. */
@Injectable({ providedIn: 'root' })
export class AuthService {
  readonly #http = inject(HttpClient);
  readonly #tokens = inject(TokenService);

  /** The refresh in flight, shared so parallel 401s trigger one refresh, not twelve. */
  #refreshing: Observable<string> | null = null;

  login(email: string, password: string): Promise<SignedIn | MfaChallenge> {
    return firstValueFrom(
      this.#http
        .post<SignedIn | MfaChallenge>('/api/auth/login', { email, password })
        .pipe(tap((r) => 'accessToken' in r && this.#tokens.set(r.accessToken, r.operator))),
    );
  }

  mfa(mfaToken: string, code: string): Promise<SignedIn> {
    return firstValueFrom(
      this.#http
        .post<SignedIn>('/api/auth/mfa', { mfaToken, code })
        .pipe(tap((r) => this.#tokens.set(r.accessToken, r.operator))),
    );
  }

  /** New access token from the refresh cookie; one call however many requests ask. */
  refresh(): Observable<string> {
    this.#refreshing ??= this.#http.post<{ accessToken: string }>('/api/auth/refresh', {}).pipe(
      map((r) => r.accessToken),
      tap((t) => this.#tokens.set(t)),
      finalize(() => (this.#refreshing = null)),
      shareReplay(1),
    );
    return this.#refreshing;
  }

  /** On page load: try the cookie once; signed out quietly if it is gone. */
  restore(): Promise<void> {
    return firstValueFrom(
      this.refresh().pipe(
        switchMap(() => this.#http.get<Operator>('/api/auth/me')),
        tap((me) => this.#tokens.operator.set(me)),
        map(() => undefined),
        catchError(() => {
          this.#tokens.clear();
          return of(undefined);
        }),
      ),
    );
  }

  logout(): Promise<void> {
    return firstValueFrom(
      this.#http.post<void>('/api/auth/logout', {}).pipe(
        catchError(() => of(undefined)),
        finalize(() => this.#tokens.clear()),
        map(() => undefined),
      ),
    );
  }

  /** Signed in (bearer) or with the enrolment-only token login's 403 carried. */
  startEnrolment(enrolToken?: string): Promise<Enrolment> {
    return firstValueFrom(this.#http.post<Enrolment>('/api/auth/mfa/enrol', enrolToken ? { enrolToken } : {}));
  }

  /** @returns the ten recovery codes, shown once */
  confirmEnrolment(code: string, enrolToken?: string): Promise<string[]> {
    return firstValueFrom(
      this.#http
        .post<{ recoveryCodes: string[] }>('/api/auth/mfa/confirm', { code, enrolToken })
        .pipe(map((r) => r.recoveryCodes ?? [])),
    );
  }

  /** A new set of recovery codes; needs a current authenticator code. */
  reissueRecoveryCodes(code: string): Promise<string[]> {
    return firstValueFrom(
      this.#http.post<{ recoveryCodes: string[] }>('/api/auth/mfa/recovery-codes', { code }).pipe(map((r) => r.recoveryCodes)),
    );
  }

  setPassword(token: string, password: string): Promise<void> {
    return firstValueFrom(this.#http.post<void>('/api/auth/set-password', { token, password }));
  }
}
