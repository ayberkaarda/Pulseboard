import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { errorInterceptor } from '../../core/http/interceptors';
import { SessionLifecycle } from '../../core/session/session-lifecycle';
import { settle } from '../../core/testing/auth-testing';
import { METRICS_RETRY_MS, ServicesStore } from './services.store';

const SERVICE_A = {
  id: '11111111-1111-4111-8111-111111111111',
  name: 'checkout',
  environment: 'PROD',
  createdAt: '2026-01-01T00:00:00Z',
};
const SERVICE_B = { ...SERVICE_A, id: '22222222-2222-4222-8222-222222222222', name: 'search' };

function metric(serviceId: string, name: string) {
  return {
    id: `${name}-id`,
    serviceId,
    name,
    unit: 'ms',
    aggregation: 'P95',
    createdAt: '2026-01-01T00:00:00Z',
  };
}

function setup() {
  TestBed.configureTestingModule({
    providers: [
      provideHttpClient(withInterceptors([errorInterceptor])),
      provideHttpClientTesting(),
    ],
  });
  return {
    store: TestBed.inject(ServicesStore),
    http: TestBed.inject(HttpTestingController),
    lifecycle: TestBed.inject(SessionLifecycle),
  };
}

describe('ServicesStore', () => {
  it('starts idle, shows loading while the request runs and then the services', () => {
    const { store, http } = setup();
    expect(store.services().status).toBe('idle');

    store.loadServices();
    expect(store.services().status).toBe('loading');

    http.expectOne('/api/v1/services').flush([SERVICE_A, SERVICE_B]);

    expect(store.services().status).toBe('loaded');
    expect(store.services().items.map((service) => service.name)).toEqual(['checkout', 'search']);
  });

  it('records the problem on failure and recovers on retry', () => {
    const { store, http } = setup();
    store.loadServices();
    http
      .expectOne('/api/v1/services')
      .flush(
        { type: 'urn:pulseboard:problem:unavailable' },
        { status: 503, statusText: 'x', headers: { 'Retry-After': '5' } },
      );

    expect(store.services().status).toBe('error');
    expect(store.services().problem?.status).toBe(503);
    expect(store.services().problem?.retryAfterSeconds).toBe(5);

    store.loadServices();
    expect(store.services().status).toBe('loading');
    expect(store.services().problem).toBeNull();
    http.expectOne('/api/v1/services').flush([SERVICE_A]);
    expect(store.services().status).toBe('loaded');
  });

  it('keeps showing earlier services when a reload fails', () => {
    const { store, http } = setup();
    store.loadServices();
    http.expectOne('/api/v1/services').flush([SERVICE_A]);

    store.loadServices();
    http.expectOne('/api/v1/services').flush(null, { status: 500, statusText: 'x' });

    expect(store.services().status).toBe('error');
    expect(store.services().items).toHaveLength(1);
  });

  it('loads the metrics of a service by id', () => {
    const { store, http } = setup();

    store.loadMetrics(SERVICE_A.id);
    expect(store.metrics()[SERVICE_A.id].status).toBe('loading');
    http
      .expectOne(`/api/v1/services/${SERVICE_A.id}/metrics`)
      .flush([metric(SERVICE_A.id, 'latency')]);

    const loaded = store.metrics()[SERVICE_A.id];
    expect(loaded.status).toBe('loaded');
    expect(loaded.items.map((item) => item.name)).toEqual(['latency']);
  });

  it('cancels the load of the service that was left and keeps the new one', () => {
    const { store, http } = setup();

    store.loadMetrics(SERVICE_A.id);
    const slow = http.expectOne(`/api/v1/services/${SERVICE_A.id}/metrics`);
    store.loadMetrics(SERVICE_B.id);
    http
      .expectOne(`/api/v1/services/${SERVICE_B.id}/metrics`)
      .flush([metric(SERVICE_B.id, 'queries')]);

    expect(slow.cancelled).toBe(true);

    expect(store.metrics()[SERVICE_B.id].items.map((item) => item.name)).toEqual(['queries']);
    expect(store.metrics()[SERVICE_A.id].items).toEqual([]);
    expect(store.metrics()[SERVICE_A.id].status).toBe('loading');
  });

  it('cancels an older services load when a newer one starts', () => {
    const { store, http } = setup();

    store.loadServices();
    const first = http.expectOne('/api/v1/services');
    store.loadServices();
    const second = http.expectOne('/api/v1/services');

    expect(first.cancelled).toBe(true);
    second.flush([SERVICE_B]);

    expect(store.services().items.map((service) => service.name)).toEqual(['search']);
  });

  describe('ensureMetrics', () => {
    const metricsUrl = (id: string) => `/api/v1/services/${id}/metrics`;

    it('asks again after a failure, but not before the retry delay and never twice at once', () => {
      jest.useFakeTimers({ now: 1_000_000, doNotFake: ['setTimeout', 'queueMicrotask'] });
      try {
        const { store, http } = setup();
        store.ensureMetrics(['s-1']);
        http.expectOne(metricsUrl('s-1')).flush(null, { status: 503, statusText: 'Unavailable' });
        expect(store.metrics()['s-1'].status).toBe('error');

        store.ensureMetrics(['s-1']);
        http.expectNone(metricsUrl('s-1'));

        jest.setSystemTime(1_000_000 + METRICS_RETRY_MS);
        store.ensureMetrics(['s-1']);
        store.ensureMetrics(['s-1']);
        const retry = http.expectOne(metricsUrl('s-1'));
        retry.flush([]);
        expect(store.metrics()['s-1'].status).toBe('loaded');

        store.ensureMetrics(['s-1']);
        http.expectNone(metricsUrl('s-1'));
      } finally {
        jest.useRealTimers();
      }
    });
  });

  it('forgets everything on logout and ignores answers that were still on their way', async () => {
    const { store, http, lifecycle } = setup();
    store.loadServices();
    http.expectOne('/api/v1/services').flush([SERVICE_A]);
    store.loadMetrics(SERVICE_A.id);
    const inFlight = http.expectOne(`/api/v1/services/${SERVICE_A.id}/metrics`);
    store.loadServices();
    const pendingServices = http.expectOne('/api/v1/services');

    lifecycle.clear();
    inFlight.flush([metric(SERVICE_A.id, 'latency')]);
    pendingServices.flush([SERVICE_B]);
    await settle();

    expect(store.services().status).toBe('idle');
    expect(store.services().items).toEqual([]);
    expect(store.metrics()).toEqual({});
  });
});
