import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, provideRouter, RouterStateSnapshot, UrlTree } from '@angular/router';
import { authGuard, guestGuard, roleGuard } from './guards';
import { TokenService } from './token.service';

describe('guards', () => {
  let tokens: TokenService;
  const route = {} as ActivatedRouteSnapshot;
  const state = (url: string) => ({ url }) as RouterStateSnapshot;
  const run = <T>(fn: () => T) => TestBed.runInInjectionContext(fn);

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideZonelessChangeDetection(), provideRouter([])] });
    tokens = TestBed.inject(TokenService);
  });

  it('authGuard sends a signed-out operator to sign-in, remembering where they were going', () => {
    const result = run(() => authGuard(route, state('/customers/abc'))) as UrlTree;
    expect(result.toString()).toBe('/login?returnUrl=%2Fcustomers%2Fabc');
  });

  it('authGuard lets a signed-in operator through', () => {
    tokens.set('t', { id: '1', email: 'v@example.com', fullName: 'V', roles: ['VIEWER'] });
    expect(run(() => authGuard(route, state('/ingest')))).toBe(true);
  });

  it('roleGuard follows the server: OWNER has everything, any role can read, nothing else is implied', () => {
    tokens.set('t', { id: '1', email: 'c@example.com', fullName: 'C', roles: ['CAMPAIGN_EDIT'] });
    expect(run(() => roleGuard('VIEWER')(route, state('/x')))).toBe(true);
    expect(run(() => roleGuard('CONFIG_ADMIN')(route, state('/x')))).toBeInstanceOf(UrlTree);
    expect(run(() => roleGuard('ANALYST')(route, state('/x')))).toBeInstanceOf(UrlTree);

    tokens.set('t', { id: '2', email: 'o@example.com', fullName: 'O', roles: ['OWNER'] });
    expect(run(() => roleGuard('CONFIG_ADMIN')(route, state('/x')))).toBe(true);
  });

  it('guestGuard keeps a signed-in operator off the sign-in page', () => {
    expect(run(() => guestGuard(route, state('/login')))).toBe(true);
    tokens.set('t', { id: '1', email: 'v@example.com', fullName: 'V', roles: ['VIEWER'] });
    expect((run(() => guestGuard(route, state('/login'))) as UrlTree).toString()).toBe('/');
  });
});
