import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { ApiError } from '../../api/problem';
import { errorInterceptor } from '../http/interceptors';
import { settle, tokenResponse } from '../testing/auth-testing';
import { REFRESH_TOKEN_STORAGE_KEY } from './refresh-token-storage';
import { SessionLifecycle } from './session-lifecycle';
import { SessionStore } from './session.store';

const LOGIN = { tenantSlug: 'acme', email: 'ada@acme.test', password: 'correct horse battery' };

function setup() {
  TestBed.configureTestingModule({
    providers: [
      provideHttpClient(withInterceptors([errorInterceptor])),
      provideHttpClientTesting(),
    ],
  });
  return {
    session: TestBed.inject(SessionStore),
    http: TestBed.inject(HttpTestingController),
    lifecycle: TestBed.inject(SessionLifecycle),
  };
}

async function signIn(session: InstanceType<typeof SessionStore>, http: HttpTestingController) {
  const done = session.login(LOGIN);
  http.expectOne('/api/v1/auth/login').flush(tokenResponse(1));
  await done;
}

describe('SessionStore', () => {
  beforeEach(() => sessionStorage.clear());
  afterEach(() => {
    jest.restoreAllMocks();
    sessionStorage.clear();
  });

  describe('login and register', () => {
    it('keeps the access token in memory only and the refresh token in sessionStorage', async () => {
      const { session, http } = setup();

      await signIn(session, http);

      expect(session.isAuthenticated()).toBe(true);
      expect(session.accessToken()).toBe('access-1');
      expect(session.user()?.role).toBe('OWNER');
      const stored = sessionStorage.getItem(REFRESH_TOKEN_STORAGE_KEY) ?? '';
      expect(stored).toContain('refresh-1');
      expect(stored).not.toContain('access-1');
      expect(JSON.stringify(localStorage)).not.toContain('access-1');
      http.verify();
    });

    it('sends the credentials as the contract defines and rejects with the decoded problem', async () => {
      const { session, http } = setup();

      const attempt = session.login(LOGIN);
      const request = http.expectOne('/api/v1/auth/login');
      expect(request.request.body).toEqual(LOGIN);
      request.flush(
        { type: 'urn:pulseboard:problem:unauthorized', title: 'Unauthorized', status: 401 },
        { status: 401, statusText: 'Unauthorized' },
      );

      const error: unknown = await attempt.catch((reason: unknown) => reason);
      expect(error).toBeInstanceOf(ApiError);
      expect((error as ApiError).problem.kind).toBe('unauthorized');
      expect(session.isAuthenticated()).toBe(false);
      expect(sessionStorage.getItem(REFRESH_TOKEN_STORAGE_KEY)).toBeNull();
    });

    it('registers and signs in with the returned pair', async () => {
      const { session, http } = setup();
      const details = {
        tenantName: 'Acme',
        tenantSlug: 'acme',
        email: 'ada@acme.test',
        password: 'correct horse battery',
      };

      const done = session.register(details);
      const request = http.expectOne('/api/v1/auth/register');
      expect(request.request.body).toEqual(details);
      request.flush(tokenResponse(1), { status: 201, statusText: 'Created' });
      await done;

      expect(session.isAuthenticated()).toBe(true);
    });

    it('refuses a token response that lacks required fields', async () => {
      const { session, http } = setup();

      const attempt = session.login(LOGIN);
      http.expectOne('/api/v1/auth/login').flush({ ...tokenResponse(1), accessToken: undefined });

      await expect(attempt).rejects.toThrow('incomplete');
      expect(session.isAuthenticated()).toBe(false);
    });
  });

  describe('refresh', () => {
    it('shares ONE request between concurrent callers and rotates the stored token', async () => {
      const { session, http } = setup();
      await signIn(session, http);

      const callers = [session.refresh(), session.refresh(), session.refresh()];
      const request = http.expectOne('/api/v1/auth/refresh');
      expect(request.request.body).toEqual({ refreshToken: 'refresh-1' });
      request.flush(tokenResponse(2));

      expect(await Promise.all(callers)).toEqual(['refreshed', 'refreshed', 'refreshed']);
      expect(session.accessToken()).toBe('access-2');
      expect(sessionStorage.getItem(REFRESH_TOKEN_STORAGE_KEY)).toContain('refresh-2');
      http.verify();

      // A later refresh is a new exchange with the rotated token.
      const next = session.refresh();
      const second = http.expectOne('/api/v1/auth/refresh');
      expect(second.request.body).toEqual({ refreshToken: 'refresh-2' });
      second.flush(tokenResponse(3));
      await next;
    });

    it('ends the session when the server rejects the refresh token', async () => {
      const { session, http, lifecycle } = setup();
      const cleared = jest.fn();
      lifecycle.onClear(cleared);
      await signIn(session, http);

      const outcome = session.refresh();
      http
        .expectOne('/api/v1/auth/refresh')
        .flush({ type: 'urn:pulseboard:problem:unauthorized' }, { status: 401, statusText: 'x' });

      expect(await outcome).toBe('ended');
      expect(session.isAuthenticated()).toBe(false);
      expect(session.refreshToken()).toBeNull();
      expect(sessionStorage.getItem(REFRESH_TOKEN_STORAGE_KEY)).toBeNull();
      expect(cleared).toHaveBeenCalledTimes(1);
    });

    it.each([
      ['a server error', { status: 503, statusText: 'Unavailable' }],
      ['rate limiting', { status: 429, statusText: 'Too Many Requests' }],
      ['no connection', { status: 0, statusText: '' }],
    ])('keeps the session when the refresh fails because of %s', async (_label, init) => {
      const { session, http } = setup();
      await signIn(session, http);

      const outcome = session.refresh();
      const request = http.expectOne('/api/v1/auth/refresh');
      if (init.status === 0) {
        request.error(new ProgressEvent('error'));
      } else {
        request.flush(null, init);
      }

      expect(await outcome).toBe('unavailable');
      expect(session.accessToken()).toBe('access-1');
      expect(session.refreshToken()).toBe('refresh-1');
      expect(sessionStorage.getItem(REFRESH_TOKEN_STORAGE_KEY)).toContain('refresh-1');
    });

    it('does not call the server with a refresh token that has expired', async () => {
      const { session, http } = setup();
      const done = session.login(LOGIN);
      http
        .expectOne('/api/v1/auth/login')
        .flush(
          tokenResponse(1, { refreshTokenExpiresAt: new Date(Date.now() - 1000).toISOString() }),
        );
      await done;

      expect(await session.refresh()).toBe('ended');
      http.expectNone('/api/v1/auth/refresh');
      expect(session.isAuthenticated()).toBe(false);
    });

    it('discards a refresh response that arrives after logout', async () => {
      const { session, http } = setup();
      await signIn(session, http);

      const outcome = session.refresh();
      const request = http.expectOne('/api/v1/auth/refresh');
      session.logout();
      request.flush(tokenResponse(2));

      expect(await outcome).toBe('ended');
      expect(session.isAuthenticated()).toBe(false);
      expect(sessionStorage.getItem(REFRESH_TOKEN_STORAGE_KEY)).toBeNull();
    });
  });

  describe('restore', () => {
    function seedStorage(token: string, expiresAt: number): void {
      sessionStorage.setItem(
        REFRESH_TOKEN_STORAGE_KEY,
        JSON.stringify({ refreshToken: token, expiresAt }),
      );
    }

    it('brings a session back after a reload from the stored refresh token', async () => {
      seedStorage('refresh-stored', Date.now() + 60_000);
      const { session, http } = setup();
      expect(session.isAuthenticated()).toBe(false);
      expect(session.canRestore()).toBe(true);

      const restored = session.restore();
      const request = http.expectOne('/api/v1/auth/refresh');
      expect(request.request.body).toEqual({ refreshToken: 'refresh-stored' });
      request.flush(tokenResponse(2));

      expect(await restored).toBe(true);
      expect(session.accessToken()).toBe('access-2');
    });

    it('ignores a stored token that already expired or is malformed', () => {
      seedStorage('refresh-old', Date.now() - 1);
      expect(setup().session.canRestore()).toBe(false);

      TestBed.resetTestingModule();
      sessionStorage.setItem(REFRESH_TOKEN_STORAGE_KEY, '{not json');
      expect(setup().session.canRestore()).toBe(false);
    });

    it('does not restore without a stored token', async () => {
      const { session, http } = setup();

      expect(await session.restore()).toBe(false);
      http.expectNone('/api/v1/auth/refresh');
    });
  });

  describe('storage failures', () => {
    it('keeps working in memory when sessionStorage rejects writes', async () => {
      jest.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
        throw new DOMException('full', 'QuotaExceededError');
      });
      const { session, http } = setup();

      await signIn(session, http);

      expect(session.isAuthenticated()).toBe(true);
      const outcome = session.refresh();
      const request = http.expectOne('/api/v1/auth/refresh');
      expect(request.request.body).toEqual({ refreshToken: 'refresh-1' });
      request.flush(tokenResponse(2));
      expect(await outcome).toBe('refreshed');
    });

    it('starts signed out when sessionStorage cannot be read, and still logs out', async () => {
      jest.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
        throw new DOMException('denied', 'SecurityError');
      });
      jest.spyOn(Storage.prototype, 'removeItem').mockImplementation(() => {
        throw new DOMException('denied', 'SecurityError');
      });
      const { session, http } = setup();

      expect(session.canRestore()).toBe(false);
      await signIn(session, http);
      expect(() => session.logout()).not.toThrow();
      expect(session.isAuthenticated()).toBe(false);
    });
  });

  describe('logout', () => {
    it('clears tokens, storage and every registered store', async () => {
      const { session, http, lifecycle } = setup();
      const resetStore = jest.fn();
      lifecycle.onClear(resetStore);
      await signIn(session, http);

      session.logout();

      expect(session.accessToken()).toBeNull();
      expect(session.refreshToken()).toBeNull();
      expect(session.user()).toBeNull();
      expect(sessionStorage.getItem(REFRESH_TOKEN_STORAGE_KEY)).toBeNull();
      expect(resetStore).toHaveBeenCalledTimes(1);
    });

    it('does not adopt a login response that finishes after logout', async () => {
      const { session, http } = setup();

      const attempt = session.login(LOGIN);
      const request = http.expectOne('/api/v1/auth/login');
      session.logout();
      request.flush(tokenResponse(1));
      await attempt;

      expect(session.isAuthenticated()).toBe(false);
    });
  });

  describe('freshAccessToken', () => {
    it('returns the current token while it has time left', async () => {
      const { session, http } = setup();
      await signIn(session, http);

      expect(await session.freshAccessToken()).toBe('access-1');
      http.expectNone('/api/v1/auth/refresh');
    });

    it('refreshes first when the token is about to expire', async () => {
      const { session, http } = setup();
      const done = session.login(LOGIN);
      http
        .expectOne('/api/v1/auth/login')
        .flush(
          tokenResponse(1, { accessTokenExpiresAt: new Date(Date.now() + 5_000).toISOString() }),
        );
      await done;

      const token = session.freshAccessToken();
      http.expectOne('/api/v1/auth/refresh').flush(tokenResponse(2));

      expect(await token).toBe('access-2');
    });

    it('is null without a session', async () => {
      const { session } = setup();
      await settle();

      expect(await session.freshAccessToken()).toBeNull();
    });
  });
});
