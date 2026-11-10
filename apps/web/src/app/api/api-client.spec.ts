import { HttpErrorResponse, HttpHeaders, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { ApiClient, buildParams, buildUrl } from './api-client';
import type { components } from './generated/schema';
import { decodeProblem, describeProblem } from './problem';

type LoginBody = components['schemas']['LoginRequest'];

describe('ApiClient', () => {
  let api: ApiClient;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    api = TestBed.inject(ApiClient);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('fills and encodes path parameters', () => {
    let result: unknown;
    api
      .get('/api/v1/services/{serviceId}/metrics', { path: { serviceId: 'a b/c' } })
      .subscribe((value) => (result = value));

    const request = http.expectOne('/api/v1/services/a%20b%2Fc/metrics');
    expect(request.request.method).toBe('GET');
    request.flush([]);
    expect(result).toEqual([]);
  });

  it('sends the typed body of a POST', () => {
    api
      .post('/api/v1/auth/login', {
        body: { tenantSlug: 'acme', email: 'a@b.co', password: 'secret-secret' },
      })
      .subscribe();

    const request = http.expectOne('/api/v1/auth/login');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({
      tenantSlug: 'acme',
      email: 'a@b.co',
      password: 'secret-secret',
    });
    request.flush({});
  });

  it('sends PUT and PATCH with their typed bodies', () => {
    api
      .patch('/api/v1/alert-rules/{id}', { path: { id: 'r1' }, body: { enabled: false } })
      .subscribe();
    const patch = http.expectOne('/api/v1/alert-rules/r1');
    expect(patch.request.method).toBe('PATCH');
    expect(patch.request.body).toEqual({ enabled: false });
    patch.flush({});

    api
      .put('/api/v1/alert-rules/{id}', {
        path: { id: 'r1' },
        body: {
          channel: 'EMAIL_LOG',
          condition: 'THRESHOLD',
          cooldownSeconds: 60,
          enabled: true,
          metricId: 'm',
          name: 'n',
          target: 't',
          version: 3,
        },
      })
      .subscribe();
    const put = http.expectOne('/api/v1/alert-rules/r1');
    expect(put.request.method).toBe('PUT');
    expect(put.request.body).toMatchObject({ version: 3 });
    put.flush({});
  });

  it('serialises query values, skips undefined and repeats arrays', () => {
    const params = buildParams({ from: '2026-01-01T00:00:00Z', step: undefined, tag: ['a', 'b'] });
    expect(params.getAll('tag')).toEqual(['a', 'b']);
    expect(params.has('step')).toBe(false);
    expect(params.get('from')).toBe('2026-01-01T00:00:00Z');
    expect(buildUrl('/x/{id}', { id: 7 })).toBe('/x/7');
    expect(() => buildUrl('/x/{id}', {})).toThrow('Missing path parameter "id"');
  });

  it('keeps the schema types strict', () => {
    // Compile time checks: tsc over the spec config rejects any line below whose directive is unused.
    const unused = (): void => {
      // @ts-expect-error not a path of the contract
      api.get('/api/v1/nope');
      // @ts-expect-error service to service routes are not reachable from the browser
      api.post('/internal/v1/anomalies/notify', { body: {} });
      // @ts-expect-error the path parameter is required
      api.get('/api/v1/services/{serviceId}/metrics');
      // @ts-expect-error the login body is required
      api.post('/api/v1/auth/login');
      // @ts-expect-error unknown field in the body
      const extra: LoginBody = { tenantSlug: 'a', email: 'b', password: 'c', x: 1 };
      api.post('/api/v1/auth/login', { body: extra });
      // @ts-expect-error the services list has no DELETE
      api.delete('/api/v1/services');
    };
    expect(typeof unused).toBe('function');
  });
});

describe('problem decoding', () => {
  function failure(status: number, error: unknown, headers?: Record<string, string>) {
    return new HttpErrorResponse({ status, error, headers: new HttpHeaders(headers) });
  }

  it('reads kind, title and field errors from problem+json', () => {
    const problem = decodeProblem(
      failure(400, {
        type: 'urn:pulseboard:problem:validation',
        title: 'Validation failed',
        errors: [
          { field: 'email', message: 'must be a well-formed email address' },
          { field: 7, message: 'dropped' },
          'dropped too',
        ],
      }),
    );

    expect(problem.kind).toBe('validation');
    expect(problem.title).toBe('Validation failed');
    expect(problem.fieldErrors).toEqual([
      { field: 'email', message: 'must be a well-formed email address' },
    ]);
  });

  it('survives bodies that are not problem documents', () => {
    expect(decodeProblem(failure(502, '<html>bad gateway</html>')).kind).toBe('unknown');
    expect(decodeProblem(failure(500, null)).title).toBe('Request failed');
    expect(decodeProblem(failure(0, new ProgressEvent('error'))).title).toBe('Network error');
  });

  it('bounds and validates Retry-After', () => {
    expect(decodeProblem(failure(429, null, { 'Retry-After': '90' })).retryAfterSeconds).toBe(90);
    expect(decodeProblem(failure(429, null, { 'Retry-After': '999999' })).retryAfterSeconds).toBe(
      3600,
    );
    expect(
      decodeProblem(failure(429, null, { 'Retry-After': 'Wed, 21 Oct 2026 07:28:00 GMT' }))
        .retryAfterSeconds,
    ).toBeUndefined();
    expect(decodeProblem(failure(429, null, { 'Retry-After': '-5' })).retryAfterSeconds).toBe(
      undefined,
    );
  });

  it('never shows server detail text to the user', () => {
    const problem = decodeProblem(
      failure(500, {
        type: 'urn:pulseboard:problem:internal-error',
        detail: 'org.postgresql.util.PSQLException: relation "x" does not exist',
      }),
    );
    expect(problem.detail).toContain('PSQLException');
    expect(describeProblem(problem)).not.toContain('PSQL');
    expect(describeProblem({ ...problem, status: 429, retryAfterSeconds: 90 })).toBe(
      'Too many requests. Try again in 2 minutes.',
    );
    expect(describeProblem({ ...problem, status: 0 })).toContain('Cannot reach');
  });
});
