import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';

import { errorInterceptor } from '../http/interceptors';
import { DEFAULT_LANDING_URL, safeReturnUrl } from '../navigation/return-url';
import { REFRESH_TOKEN_STORAGE_KEY } from '../session/refresh-token-storage';
import { SessionStore } from '../session/session.store';
import { StubPage, settle, tokenResponse } from '../testing/auth-testing';
import { authGuard, guestGuard } from './auth.guards';

function setup() {
  TestBed.configureTestingModule({
    providers: [
      provideHttpClient(withInterceptors([errorInterceptor])),
      provideHttpClientTesting(),
      provideRouter([
        { path: 'login', component: StubPage, canActivate: [guestGuard] },
        { path: 'register', component: StubPage, canActivate: [guestGuard] },
        { path: 'app/overview', component: StubPage, canActivate: [authGuard] },
        { path: 'app/anomalies', component: StubPage, canActivate: [authGuard] },
        { path: 'app/services/:id', component: StubPage, canActivate: [authGuard] },
      ]),
    ],
  });
  return {
    router: TestBed.inject(Router),
    http: TestBed.inject(HttpTestingController),
    session: TestBed.inject(SessionStore),
  };
}

async function signIn(session: InstanceType<typeof SessionStore>, http: HttpTestingController) {
  const done = session.login({ tenantSlug: 'acme', email: 'a@acme.test', password: 'x' });
  http.expectOne('/api/v1/auth/login').flush(tokenResponse(1));
  await done;
}

describe('authGuard', () => {
  beforeEach(() => sessionStorage.clear());

  it('redirects visitors without a session to /login and remembers where they were going', async () => {
    const { router } = setup();

    await router.navigateByUrl('/app/anomalies?status=open');

    expect(router.url).toBe('/login?returnUrl=%2Fapp%2Fanomalies%3Fstatus%3Dopen');
  });

  it('omits the return URL when the target is the default landing page', async () => {
    const { router } = setup();

    await router.navigateByUrl(DEFAULT_LANDING_URL);

    expect(router.url).toBe('/login');
  });

  it('lets a signed-in person through', async () => {
    const { router, session, http } = setup();
    await signIn(session, http);

    await router.navigateByUrl('/app/anomalies');

    expect(router.url).toBe('/app/anomalies');
  });

  it('restores a session from the stored refresh token before deciding', async () => {
    sessionStorage.setItem(
      REFRESH_TOKEN_STORAGE_KEY,
      JSON.stringify({ refreshToken: 'refresh-stored', expiresAt: Date.now() + 60_000 }),
    );
    const { router, http } = setup();

    const navigation = router.navigateByUrl('/app/anomalies');
    await settle();
    http.expectOne('/api/v1/auth/refresh').flush(tokenResponse(2));
    await navigation;

    expect(router.url).toBe('/app/anomalies');
  });

  it('sends the person to login when the stored refresh token is rejected', async () => {
    sessionStorage.setItem(
      REFRESH_TOKEN_STORAGE_KEY,
      JSON.stringify({ refreshToken: 'refresh-stored', expiresAt: Date.now() + 60_000 }),
    );
    const { router, http } = setup();

    const navigation = router.navigateByUrl('/app/anomalies');
    await settle();
    http.expectOne('/api/v1/auth/refresh').flush(null, { status: 401, statusText: 'x' });
    await navigation;

    expect(router.url).toBe('/login?returnUrl=%2Fapp%2Fanomalies');
  });
});

describe('guestGuard', () => {
  beforeEach(() => sessionStorage.clear());

  it('shows login and register to people without a session', async () => {
    const { router } = setup();

    await router.navigateByUrl('/login');
    expect(router.url).toBe('/login');

    await router.navigateByUrl('/register');
    expect(router.url).toBe('/register');
  });

  it('sends signed-in people to the default landing page', async () => {
    const { router, session, http } = setup();
    await signIn(session, http);

    await router.navigateByUrl('/login');

    expect(router.url).toBe(DEFAULT_LANDING_URL);
  });

  it('honours a safe return URL for signed-in people', async () => {
    const { router, session, http } = setup();
    await signIn(session, http);

    await router.navigateByUrl('/login?returnUrl=%2Fapp%2Fanomalies');

    expect(router.url).toBe('/app/anomalies');
  });

  it.each([
    '//evil.example',
    'https://evil.example',
    '/\\evil.example',
    'javascript:alert(1)',
    '/login',
  ])('ignores the open redirect attempt %s', async (attempt) => {
    const { router, session, http } = setup();
    await signIn(session, http);

    await router.navigateByUrl(`/login?returnUrl=${encodeURIComponent(attempt)}`);

    expect(router.url).toBe(DEFAULT_LANDING_URL);
  });
});

describe('safeReturnUrl', () => {
  it.each([
    ['/app/overview', '/app/overview'],
    ['/app', '/app'],
    ['/app/anomalies?status=open#top', '/app/anomalies?status=open#top'],
    ['/app/services/3f2a', '/app/services/3f2a'],
    ['/app/../app/overview', '/app/overview'],
  ])('accepts %s', (raw, expected) => {
    expect(safeReturnUrl(raw)).toBe(expected);
  });

  it.each([
    ['protocol relative', '//evil.example'],
    ['protocol relative with path', '//evil.example/app/overview'],
    ['absolute https', 'https://evil.example/app/overview'],
    ['absolute http', 'http://evil.example'],
    ['backslash host', '/\\evil.example'],
    ['backslash after slash', '/app\\..\\..\\evil'],
    ['javascript scheme', 'javascript:alert(1)'],
    ['data scheme', 'data:text/html,<script>alert(1)</script>'],
    ['tab inside the slashes', '/\t/evil.example'],
    ['newline inside the slashes', '/\n/evil.example'],
    ['leading space', ' /app/overview'],
    ['relative path', 'app/overview'],
    ['empty', ''],
    ['the login page', '/login'],
    ['the register page', '/register'],
    ['outside the app', '/healthz'],
    ['a sibling prefix', '/application/overview'],
    ['dot segments leaving the app', '/app/../login'],
    ['encoded dot segments leaving the app', '/app/%2e%2e/login'],
    ['too long', `/app/${'a'.repeat(3000)}`],
  ])('rejects %s', (_label, raw) => {
    expect(safeReturnUrl(raw)).toBeNull();
  });

  it.each([null, undefined, 42, {}, ['/app/overview']])('rejects the non-string %p', (raw) => {
    expect(safeReturnUrl(raw)).toBeNull();
  });
});
