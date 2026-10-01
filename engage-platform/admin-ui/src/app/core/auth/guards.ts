import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { Role } from '../api/models';
import { TokenService } from './token.service';

// Guards are a UX affordance, not security: admin-api enforces every role on
// every endpoint. They only stop navigation to a page that would answer 403.

export const authGuard: CanActivateFn = (_route, state) => {
  const tokens = inject(TokenService);
  return tokens.isAuthenticated()
    ? true
    : inject(Router).createUrlTree(['/login'], { queryParams: { returnUrl: state.url } });
};

export const roleGuard =
  (role: Role): CanActivateFn =>
  () =>
    inject(TokenService).has(role) ? true : inject(Router).createUrlTree(['/']);

/** Sign-in pages: an operator already signed in goes to the console. */
export const guestGuard: CanActivateFn = () =>
  inject(TokenService).isAuthenticated() ? inject(Router).createUrlTree(['/']) : true;
