import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';

import { DEFAULT_LANDING_URL, safeReturnUrl } from '../navigation/return-url';
import { SessionStore } from '../session/session.store';

/** Signed-in area: restores a session from the stored refresh token, else goes to /login. */
export const authGuard: CanActivateFn = async (_route, state) => {
  const session = inject(SessionStore);
  const router = inject(Router);
  if (await session.restore()) {
    return true;
  }
  const returnUrl = safeReturnUrl(state.url);
  return router.createUrlTree(['/login'], {
    queryParams: returnUrl && returnUrl !== DEFAULT_LANDING_URL ? { returnUrl } : {},
  });
};

/** Login and register: people who already have a session go to where they were headed. */
export const guestGuard: CanActivateFn = async (route) => {
  const session = inject(SessionStore);
  const router = inject(Router);
  if (!(await session.restore())) {
    return true;
  }
  const target = safeReturnUrl(route.queryParamMap.get('returnUrl')) ?? DEFAULT_LANDING_URL;
  return router.parseUrl(target);
};
