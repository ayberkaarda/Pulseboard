/** Where people land after signing in when no usable return URL was given. */
export const DEFAULT_LANDING_URL = '/app/overview';

const PLACEHOLDER_ORIGIN = 'http://pulseboard.invalid';
const MAX_LENGTH = 2048;
const BACKSLASH = String.fromCharCode(92);
// eslint-disable-next-line no-control-regex
const CONTROL_CHARACTERS = /[\u0000-\u001f\u007f]/;

/**
 * Accepts only a path inside the signed-in area (/app...). Everything else returns null, so a crafted
 * `?returnUrl=` can never send someone to another site or back into the login page:
 * //evil.com, https://evil.com, /\evil.com, javascript:..., tab/newline tricks and dot segments.
 * The returned value is the normalised path, query and fragment.
 */
export function safeReturnUrl(raw: unknown): string | null {
  if (typeof raw !== 'string' || raw.length === 0 || raw.length > MAX_LENGTH) {
    return null;
  }
  if (
    !raw.startsWith('/') ||
    raw.startsWith('//') ||
    raw.includes(BACKSLASH) ||
    CONTROL_CHARACTERS.test(raw)
  ) {
    return null;
  }
  let url: URL;
  try {
    url = new URL(raw, PLACEHOLDER_ORIGIN);
  } catch {
    return null;
  }
  if (url.origin !== PLACEHOLDER_ORIGIN) {
    return null;
  }
  const insideApp = url.pathname === '/app' || url.pathname.startsWith('/app/');
  return insideApp ? `${url.pathname}${url.search}${url.hash}` : null;
}
