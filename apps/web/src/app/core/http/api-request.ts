import { HttpContext, HttpContextToken } from '@angular/common/http';

/** Set on requests whose caller shows failures itself (inline form errors, page level error states). */
export const SKIP_ERROR_TOAST = new HttpContextToken<boolean>(() => false);

/** Context for requests whose failures the caller presents itself. */
export function quietContext(): HttpContext {
  return new HttpContext().set(SKIP_ERROR_TOAST, true);
}

/** Set on the single replay of a request after a token refresh, so it can never loop. */
export const IS_REPLAY = new HttpContextToken<boolean>(() => false);

const FALLBACK_ORIGIN = 'http://pulseboard.invalid';

function resolve(url: string): URL | null {
  try {
    const origin = globalThis.location?.origin;
    return new URL(url, origin && origin !== 'null' ? origin : FALLBACK_ORIGIN);
  } catch {
    return null;
  }
}

/** True only for same-origin requests under /api/. Credentials are never attached anywhere else. */
export function isApiRequest(url: string): boolean {
  const parsed = resolve(url);
  const origin = globalThis.location?.origin;
  const expected = origin && origin !== 'null' ? origin : FALLBACK_ORIGIN;
  return parsed !== null && parsed.origin === expected && parsed.pathname.startsWith('/api/');
}

/** Register, login and refresh: public endpoints that never carry a bearer and never trigger a refresh. */
export function isAuthEndpoint(url: string): boolean {
  const parsed = resolve(url);
  return parsed !== null && parsed.pathname.startsWith('/api/v1/auth/');
}
