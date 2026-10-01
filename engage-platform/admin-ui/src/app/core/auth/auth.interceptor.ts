import { HttpErrorResponse, HttpInterceptorFn, HttpRequest } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, switchMap, throwError } from 'rxjs';
import { AuthService } from './auth.service';
import { TokenService } from './token.service';

/**
 * Adds the bearer token to /api calls. On a 401 it refreshes once (the
 * refresh itself is shared across parallel requests) and retries; if the
 * refresh fails too, the session is over and the operator goes to sign-in.
 * /api/auth calls are never retried: their 401 is the answer.
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  if (!req.url.startsWith('/api/')) return next(req);
  const tokens = inject(TokenService);
  const auth = inject(AuthService);
  const router = inject(Router);

  const withToken = (r: HttpRequest<unknown>, t: string | null) =>
    t ? r.clone({ setHeaders: { Authorization: `Bearer ${t}` } }) : r;

  return next(withToken(req, tokens.token())).pipe(
    catchError((err: HttpErrorResponse) => {
      if (err.status !== 401 || req.url.startsWith('/api/auth/')) return throwError(() => err);
      return auth.refresh().pipe(
        catchError((refreshErr) => {
          tokens.clear();
          void router.navigate(['/login'], { queryParams: { returnUrl: router.url } });
          return throwError(() => refreshErr);
        }),
        switchMap((t) => next(withToken(req, t))),
      );
    }),
  );
};
