import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';

import { ApiError } from '../../api/problem';
import { Notifier } from '../notify/notifier.service';
import { REFRESH_TOKEN_STORAGE_KEY } from '../session/refresh-token-storage';
import { SessionStore } from '../session/session.store';
import { StubPage, settle, tokenResponse } from '../testing/auth-testing';
import { quietContext } from './api-request';
import {
  SessionChangedError,
  authInterceptor,
  errorInterceptor,
  refreshInterceptor,
} from './interceptors';

const LOGIN = { tenantSlug: 'acme', email: 'ada@acme.test', password: 'correct horse battery' };

async function setup(options: { signedIn?: boolean; at?: string } = {}) {
  const notifier = { error: jest.fn(), info: jest.fn() };
  TestBed.configureTestingModule({
    providers: [
      provideHttpClient(withInterceptors([errorInterceptor, refreshInterceptor, authInterceptor])),
      provideHttpClientTesting(),
      provideRouter([
        { path: 'login', component: StubPage },
        { path: 'app/overview', component: StubPage },
        { path: 'app/anomalies', component: StubPage },
      ]),
      { provide: Notifier, useValue: notifier },
    ],
  });
  const http = TestBed.inject(HttpTestingController);
  const client = TestBed.inject(HttpClient);
  const session = TestBed.inject(SessionStore);
  const router = TestBed.inject(Router);
  await router.navigateByUrl(options.at ?? '/app/overview');
  if (options.signedIn !== false) {
    const done = session.login(LOGIN);
    http.expectOne('/api/v1/auth/login').flush(tokenResponse(1));
    await done;
  }
  return { http, client, session, router, notifier };
}

function unauthorized() {
  return {
    status: 401,
    statusText: 'Unauthorized',
  };
}

describe('auth interceptor', () => {
  beforeEach(() => sessionStorage.clear());

  it('attaches the bearer token to same-origin /api/ requests only', async () => {
    const { http, client } = await setup();

    client.get('/api/v1/services').subscribe();
    expect(http.expectOne('/api/v1/services').request.headers.get('Authorization')).toBe(
      'Bearer access-1',
    );

    client.get('/assets/config.json').subscribe();
    expect(http.expectOne('/assets/config.json').request.headers.has('Authorization')).toBe(false);

    client.get('https://other.example/api/v1/services').subscribe();
    expect(
      http.expectOne('https://other.example/api/v1/services').request.headers.has('Authorization'),
    ).toBe(false);

    client.get('//other.example/api/v1/services').subscribe();
    expect(
      http.expectOne('//other.example/api/v1/services').request.headers.has('Authorization'),
    ).toBe(false);

    client.get('/apix/v1/services').subscribe();
    expect(http.expectOne('/apix/v1/services').request.headers.has('Authorization')).toBe(false);
  });

  it('does not attach a bearer to the public auth endpoints', async () => {
    const { http, client } = await setup();

    client.post('/api/v1/auth/login', LOGIN).subscribe({ error: () => undefined });

    expect(http.expectOne('/api/v1/auth/login').request.headers.has('Authorization')).toBe(false);
  });

  it('sends no header without a session', async () => {
    const { http, client } = await setup({ signedIn: false });

    client.get('/api/v1/services').subscribe({ error: () => undefined });

    expect(http.expectOne('/api/v1/services').request.headers.has('Authorization')).toBe(false);
  });
});

describe('refresh interceptor', () => {
  beforeEach(() => sessionStorage.clear());

  it('refreshes once after a 401 and replays the request with the new token', async () => {
    const { http, client } = await setup();
    let body: unknown;
    client.get('/api/v1/services').subscribe((value) => (body = value));

    http.expectOne('/api/v1/services').flush(null, unauthorized());
    http.expectOne('/api/v1/auth/refresh').flush(tokenResponse(2));
    await settle();
    const replay = http.expectOne('/api/v1/services');
    expect(replay.request.headers.get('Authorization')).toBe('Bearer access-2');
    replay.flush([{ id: 'a' }]);

    expect(body).toEqual([{ id: 'a' }]);
    http.verify();
  });

  it('shares one refresh between concurrent 401s and replays every request', async () => {
    const { http, client } = await setup();
    const results: string[] = [];
    for (const name of ['a', 'b', 'c']) {
      client.get(`/api/v1/${name}`).subscribe(() => results.push(name));
    }

    for (const name of ['a', 'b', 'c']) {
      http.expectOne(`/api/v1/${name}`).flush(null, unauthorized());
    }
    const refresh = http.match('/api/v1/auth/refresh');
    expect(refresh).toHaveLength(1);
    expect(refresh[0].request.body).toEqual({ refreshToken: 'refresh-1' });
    refresh[0].flush(tokenResponse(2));
    await settle();

    for (const name of ['a', 'b', 'c']) {
      const replay = http.expectOne(`/api/v1/${name}`);
      expect(replay.request.headers.get('Authorization')).toBe('Bearer access-2');
      replay.flush({});
    }
    expect(results.sort()).toEqual(['a', 'b', 'c']);
    http.verify();
  });

  it('replays a request that was rejected with a token another request already replaced', async () => {
    const { http, client, session } = await setup();
    client.get('/api/v1/slow').subscribe();
    const slow = http.expectOne('/api/v1/slow');

    const rotation = session.refresh();
    http.expectOne('/api/v1/auth/refresh').flush(tokenResponse(2));
    await rotation;
    slow.flush(null, unauthorized());
    await settle();

    // No second rotation: the old token would be a reuse in the server's eyes.
    http.expectNone('/api/v1/auth/refresh');
    expect(http.expectOne('/api/v1/slow').request.headers.get('Authorization')).toBe(
      'Bearer access-2',
    );
  });

  it('does not retry again when the replay is rejected as well', async () => {
    const { http, client, session } = await setup();
    let error: unknown;
    client.get('/api/v1/services').subscribe({ error: (e: unknown) => (error = e) });

    http.expectOne('/api/v1/services').flush(null, unauthorized());
    http.expectOne('/api/v1/auth/refresh').flush(tokenResponse(2));
    await settle();
    http.expectOne('/api/v1/services').flush(null, unauthorized());
    await settle();

    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).problem.status).toBe(401);
    http.expectNone('/api/v1/services');
    http.expectNone('/api/v1/auth/refresh');
    expect(session.accessToken()).toBe('access-2');
  });

  it('signs out and sends the person to login with a return URL when the refresh is rejected', async () => {
    const { http, client, session, router, notifier } = await setup({ at: '/app/anomalies' });
    let error: unknown;
    client.get('/api/v1/anomalies').subscribe({ error: (e: unknown) => (error = e) });

    http.expectOne('/api/v1/anomalies').flush(null, unauthorized());
    http.expectOne('/api/v1/auth/refresh').flush(null, unauthorized());
    await settle();

    expect(session.isAuthenticated()).toBe(false);
    expect(sessionStorage.getItem(REFRESH_TOKEN_STORAGE_KEY)).toBeNull();
    expect(router.url).toBe('/login?returnUrl=%2Fapp%2Fanomalies');
    expect(notifier.info).toHaveBeenCalledWith('Your session has ended. Sign in again.');
    expect(error).toBeInstanceOf(ApiError);
    http.verify();
  });

  it('keeps the session and surfaces the original 401 when the refresh is only unavailable', async () => {
    const { http, client, session, router } = await setup({ at: '/app/anomalies' });
    let error: unknown;
    client.get('/api/v1/anomalies').subscribe({ error: (e: unknown) => (error = e) });

    http.expectOne('/api/v1/anomalies').flush(null, unauthorized());
    http
      .expectOne('/api/v1/auth/refresh')
      .flush(null, { status: 503, statusText: 'Service Unavailable' });
    await settle();

    expect(session.refreshToken()).toBe('refresh-1');
    expect(router.url).toBe('/app/anomalies');
    expect((error as ApiError).problem.status).toBe(401);
    http.verify();
  });

  it('replays a POST after a 401 (the server rejected it before running it)', async () => {
    const { http, client } = await setup();
    client.post('/api/v1/services', { name: 'api' }).subscribe();

    http.expectOne('/api/v1/services').flush(null, unauthorized());
    http.expectOne('/api/v1/auth/refresh').flush(tokenResponse(2));
    await settle();

    const replay = http.expectOne('/api/v1/services');
    expect(replay.request.method).toBe('POST');
    expect(replay.request.body).toEqual({ name: 'api' });
  });

  it('never replays a late 401 of user A with the bearer of user B who signed in meanwhile', async () => {
    const { http, client, session } = await setup();
    let error: unknown;
    client.post('/api/v1/anomalies/a1/acknowledge', {}).subscribe({ error: (e) => (error = e) });
    const late = http.expectOne('/api/v1/anomalies/a1/acknowledge');

    session.logout();
    http.expectOne('/api/v1/auth/logout').flush(null);
    const signIn = session.login(LOGIN);
    http.expectOne('/api/v1/auth/login').flush(tokenResponse(7));
    await signIn;
    expect(session.accessToken()).toBe('access-7');

    late.flush(null, unauthorized());
    await settle();

    expect(error).toBeInstanceOf(SessionChangedError);
    http.expectNone('/api/v1/anomalies/a1/acknowledge');
    http.expectNone('/api/v1/auth/refresh');
    expect(session.accessToken()).toBe('access-7');
  });

  it('drops the request when the user signs out while its refresh is in flight', async () => {
    const { http, client, session, notifier } = await setup();
    let error: unknown;
    client.get('/api/v1/services').subscribe({ error: (e) => (error = e) });
    http.expectOne('/api/v1/services').flush(null, unauthorized());
    const refresh = http.expectOne('/api/v1/auth/refresh');

    session.logout();
    http.expectOne('/api/v1/auth/logout').flush(null);
    refresh.flush(tokenResponse(2));
    await settle();

    expect(error).toBeInstanceOf(SessionChangedError);
    http.expectNone('/api/v1/services');
    expect(session.accessToken()).toBeNull();
    expect(notifier.info).not.toHaveBeenCalled();
  });

  it('still replays after a refresh that keeps the same identity', async () => {
    const { http, client, session } = await setup();
    const before = session.identityEpoch();
    client.get('/api/v1/services').subscribe();
    http.expectOne('/api/v1/services').flush(null, unauthorized());
    http.expectOne('/api/v1/auth/refresh').flush(tokenResponse(2));
    await settle();
    expect(session.identityEpoch()).toBe(before);
    expect(http.expectOne('/api/v1/services').request.headers.get('Authorization')).toBe(
      'Bearer access-2',
    );
  });

  it.each([
    ['GET', 500],
    ['POST', 500],
    ['POST', 503],
    ['GET', 403],
    ['POST', 0],
  ])('never replays a %s that failed with %i', async (method, status) => {
    const { http, client } = await setup();
    let error: unknown;
    client
      .request(method, '/api/v1/services', { body: method === 'POST' ? {} : undefined })
      .subscribe({ error: (e: unknown) => (error = e) });

    const request = http.expectOne('/api/v1/services');
    if (status === 0) {
      request.error(new ProgressEvent('error'));
    } else {
      request.flush(null, { status, statusText: 'x' });
    }
    await settle();

    expect(error).toBeInstanceOf(ApiError);
    http.expectNone('/api/v1/services');
    http.expectNone('/api/v1/auth/refresh');
  });

  it('does not try to refresh when the login itself is rejected', async () => {
    const { http, client } = await setup();
    let error: unknown;
    client.post('/api/v1/auth/login', LOGIN).subscribe({ error: (e: unknown) => (error = e) });

    http.expectOne('/api/v1/auth/login').flush(null, unauthorized());
    await settle();

    http.expectNone('/api/v1/auth/refresh');
    expect((error as ApiError).problem.status).toBe(401);
  });

  it('does not refresh for a request that carried no session', async () => {
    const { http, client } = await setup({ signedIn: false });
    client.get('/api/v1/services').subscribe({ error: () => undefined });

    http.expectOne('/api/v1/services').flush(null, unauthorized());
    await settle();

    http.expectNone('/api/v1/auth/refresh');
  });
});

describe('error interceptor', () => {
  beforeEach(() => sessionStorage.clear());

  it('maps 429 with Retry-After to a snackbar message and an ApiError', async () => {
    const { http, client, notifier } = await setup();
    let error: unknown;
    client.get('/api/v1/services').subscribe({ error: (e: unknown) => (error = e) });

    http
      .expectOne('/api/v1/services')
      .flush(
        { type: 'urn:pulseboard:problem:rate-limited', title: 'Too Many Requests' },
        { status: 429, statusText: 'Too Many Requests', headers: { 'Retry-After': '90' } },
      );

    expect(notifier.error).toHaveBeenCalledWith('Too many requests. Try again in 2 minutes.');
    expect((error as ApiError).problem.kind).toBe('rate-limited');
    expect((error as ApiError).problem.retryAfterSeconds).toBe(90);
  });

  it('maps 503 and network failures to their own messages', async () => {
    const { http, client, notifier } = await setup();
    client.get('/api/v1/a').subscribe({ error: () => undefined });
    http
      .expectOne('/api/v1/a')
      .flush(
        { type: 'urn:pulseboard:problem:unavailable' },
        { status: 503, statusText: 'x', headers: { 'Retry-After': '5' } },
      );
    client.get('/api/v1/b').subscribe({ error: () => undefined });
    http.expectOne('/api/v1/b').error(new ProgressEvent('error'));

    expect(notifier.error).toHaveBeenNthCalledWith(
      1,
      'Pulseboard is temporarily unavailable. Try again in 5 seconds.',
    );
    expect(notifier.error).toHaveBeenNthCalledWith(
      2,
      'Cannot reach Pulseboard. Check your connection and try again.',
    );
  });

  it('never shows the raw server detail', async () => {
    const { http, client, notifier } = await setup();
    client.get('/api/v1/services').subscribe({ error: () => undefined });

    http.expectOne('/api/v1/services').flush(
      {
        type: 'urn:pulseboard:problem:internal-error',
        detail: 'PSQLException: relation "metric_point" does not exist',
      },
      { status: 500, statusText: 'x' },
    );

    expect(notifier.error).toHaveBeenCalledTimes(1);
    expect(String(notifier.error.mock.calls[0][0])).not.toMatch(/PSQL|metric_point/);
  });

  it('leaves validation, not found and conflict to the caller', async () => {
    const { http, client, notifier } = await setup();
    for (const status of [400, 404, 409, 422]) {
      client.get(`/api/v1/s${status}`).subscribe({ error: () => undefined });
      http.expectOne(`/api/v1/s${status}`).flush(null, { status, statusText: 'x' });
    }

    expect(notifier.error).not.toHaveBeenCalled();
  });

  it('stays silent for requests whose caller presents the error itself', async () => {
    const { http, client, notifier } = await setup();
    let error: unknown;
    client
      .get('/api/v1/services', { context: quietContext() })
      .subscribe({ error: (e: unknown) => (error = e) });

    http.expectOne('/api/v1/services').flush(null, { status: 503, statusText: 'x' });

    expect(notifier.error).not.toHaveBeenCalled();
    expect(error).toBeInstanceOf(ApiError);
  });

  it('does not touch failures of requests outside /api/', async () => {
    const { http, client, notifier } = await setup();
    let error: unknown;
    client.get('/somewhere.json').subscribe({ error: (e: unknown) => (error = e) });

    http.expectOne('/somewhere.json').flush(null, { status: 500, statusText: 'x' });

    expect(notifier.error).not.toHaveBeenCalled();
    expect(error).not.toBeInstanceOf(ApiError);
  });
});
