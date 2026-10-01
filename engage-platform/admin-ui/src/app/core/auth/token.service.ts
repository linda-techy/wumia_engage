import { computed, Injectable, signal } from '@angular/core';
import { Operator, Role } from '../api/models';

/**
 * The signed-in operator and the access token, in memory only. Never
 * localStorage: the console renders customer-supplied strings, and one missed
 * escape would turn an XSS into token theft. The refresh token lives in an
 * HttpOnly cookie this page cannot read; a reload restores the session with
 * one silent refresh.
 */
@Injectable({ providedIn: 'root' })
export class TokenService {
  readonly #accessToken = signal<string | null>(null);
  readonly operator = signal<Operator | null>(null);

  readonly isAuthenticated = computed(() => this.#accessToken() !== null);
  readonly roles = computed(() => this.operator()?.roles ?? []);

  /** Mirrors admin-api: OWNER holds everything; any role includes reading. */
  has(role: Role): boolean {
    const roles = this.roles();
    if (roles.includes(role) || roles.includes('OWNER')) return true;
    return role === 'VIEWER' && roles.length > 0;
  }

  token(): string | null {
    return this.#accessToken();
  }

  set(token: string, operator?: Operator): void {
    this.#accessToken.set(token);
    if (operator) this.operator.set(operator);
  }

  clear(): void {
    this.#accessToken.set(null);
    this.operator.set(null);
  }
}
