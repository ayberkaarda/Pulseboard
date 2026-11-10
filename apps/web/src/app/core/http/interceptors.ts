import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, from, switchMap, throwError } from 'rxjs';

import { ApiError, ApiProblem, decodeProblem, describeProblem } from '../../api/problem';
import { DEFAULT_LANDING_URL, safeReturnUrl } from '../navigation/return-url';
import { Notifier } from '../notify/notifier.service';
import { SessionStore } from '../session/session.store';
import { SKIP_ERROR_TOAST, isApiRequest, isAuthEndpoint } from './api-request';

/** Adds the bearer token to same-origin /api/ requests, except the public auth endpoints. */
export const authInterceptor: HttpInterceptorFn = (request, next) => {
  if (!isApiRequest(request.url) || isAuthEndpoint(request.url)) {
    return next(request);
  }
  const token = inject(SessionStore).accessToken();
  return next(
    token ? request.clone({ setHeaders: { Authorization: `Bearer ${token}` } }) : request,
  );
};

/**
 * Replays a request once after a 401, using a single shared refresh. A 401 means the server
 * rejected the credential before running the request, so replaying is safe for every method.
 * A replay that fails again is not retried, and nothing other than a 401 is ever replayed.
 */
export const refreshInterceptor: HttpInterceptorFn = (request, next) => {
  if (!isApiRequest(request.url) || isAuthEndpoint(request.url)) {
    return next(request);
  }
  const session = inject(SessionStore);
  const router = inject(Router);
  const notifier = inject(Notifier);
  const sentWith = session.accessToken();

  return next(request).pipe(
    catchError((error: unknown) => {
      if (!(error instanceof HttpErrorResponse) || error.status !== 401 || sentWith === null) {
        return throwError(() => error);
      }
      // The replay goes straight to the next handler, never back through this interceptor, so a
      // request is replayed at most once.
      const replay = () => next(request);
      // Another request already rotated the tokens while this one was in flight.
      if (session.accessToken() !== null && session.accessToken() !== sentWith) {
        return replay();
      }
      return from(session.refresh()).pipe(
        switchMap((outcome) => {
          if (outcome === 'refreshed') {
            return replay();
          }
          if (outcome === 'ended') {
            void sendToLogin(router, notifier);
          }
          return throwError(() => error);
        }),
      );
    }),
  );
};

async function sendToLogin(router: Router, notifier: Notifier): Promise<void> {
  if (router.url.startsWith('/login')) {
    return;
  }
  void notifier.info('Your session has ended. Sign in again.');
  const returnUrl = safeReturnUrl(router.url);
  await router.navigate(['/login'], {
    queryParams: returnUrl && returnUrl !== DEFAULT_LANDING_URL ? { returnUrl } : {},
  });
}

function shouldNotify(problem: ApiProblem): boolean {
  const { status } = problem;
  return status === 0 || status === 403 || status === 429 || status >= 500;
}

/**
 * Outermost interceptor: decodes problem+json into an ApiError for the caller and shows a snackbar
 * for failures nobody can handle locally (offline, 403, 429, 5xx). Requests flagged with
 * SKIP_ERROR_TOAST present their own errors. Validation, 404 and 409 are left to the caller.
 */
export const errorInterceptor: HttpInterceptorFn = (request, next) => {
  const notifier = inject(Notifier);
  return next(request).pipe(
    catchError((error: unknown) => {
      if (!(error instanceof HttpErrorResponse) || !isApiRequest(request.url)) {
        return throwError(() => error);
      }
      const problem = decodeProblem(error);
      if (!request.context.get(SKIP_ERROR_TOAST) && shouldNotify(problem)) {
        void notifier.error(describeProblem(problem));
      }
      return throwError(() => new ApiError(problem));
    }),
  );
};
