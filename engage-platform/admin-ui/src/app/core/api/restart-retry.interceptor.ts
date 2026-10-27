import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { retry, timer } from 'rxjs';

/** Gateway answers while admin-api restarts (a deploy takes 30–60 s). */
const RESTARTING = new Set([0, 502, 503, 504]);
/** 1 s, 2 s, 4 s: about 7 s, enough for most restarts; a longer one shows the page's error and its Retry. */
export const RETRY_DELAYS_MS = [1000, 2000, 4000];

/**
 * Reads (GET) ride out an admin-api restart instead of flashing an error on
 * every open page. Writes are never retried here: a POST that timed out may
 * have happened, so the person decides.
 */
export const restartRetryInterceptor: HttpInterceptorFn = (req, next) => {
  if (req.method !== 'GET' || !req.url.startsWith('/api/')) return next(req);
  return next(req).pipe(
    retry({
      count: RETRY_DELAYS_MS.length,
      delay: (error, attempt) => {
        if (!(error instanceof HttpErrorResponse) || !RESTARTING.has(error.status)) throw error;
        return timer(RETRY_DELAYS_MS[attempt - 1]);
      },
    }),
  );
};
