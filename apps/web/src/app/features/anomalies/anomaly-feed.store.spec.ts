import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { errorInterceptor } from '../../core/http/interceptors';
import { SessionLifecycle } from '../../core/session/session-lifecycle';
import { SessionStore } from '../../core/session/session.store';
import { AnomalyBus } from './anomaly-bus';
import { AnomalyFeedStore, MAX_ITEMS, PAGE_SIZE } from './anomaly-feed.store';
import { SourceLog, anomaly, fakeSession, rawAnomaly } from './anomaly-testing';
import {
  AnomalyStreamService,
  EVENT_SOURCE_FACTORY,
  STREAM_RANDOM,
} from './anomaly-stream.service';

function setup() {
  const log = new SourceLog();
  TestBed.configureTestingModule({
    providers: [
      provideHttpClient(withInterceptors([errorInterceptor])),
      provideHttpClientTesting(),
      provideRouter([]),
      { provide: SessionStore, useValue: fakeSession('OWNER') },
      { provide: EVENT_SOURCE_FACTORY, useValue: log.factory },
      { provide: STREAM_RANDOM, useValue: () => 1 },
    ],
  });
  return {
    log,
    http: TestBed.inject(HttpTestingController),
    store: TestBed.inject(AnomalyFeedStore),
    bus: TestBed.inject(AnomalyBus),
    stream: TestBed.inject(AnomalyStreamService),
  };
}

function page(items: object[], overrides: { totalItems?: number; totalPages?: number } = {}) {
  return {
    items,
    page: 0,
    size: PAGE_SIZE,
    totalItems: overrides.totalItems ?? items.length,
    totalPages: overrides.totalPages ?? 1,
  };
}

function listRequest(http: HttpTestingController) {
  return http.expectOne((req) => req.url === '/api/v1/anomalies');
}

async function settle(): Promise<void> {
  await Promise.resolve();
  await new Promise((resolve) => setTimeout(resolve, 0));
}

describe('AnomalyFeedStore', () => {
  afterEach(() => TestBed.inject(AnomalyStreamService).shutdown());

  it('loads one page with the filters as query parameters', () => {
    const { store, http } = setup();
    store.load();
    const first = listRequest(http);
    expect(first.request.params.get('size')).toBe(String(PAGE_SIZE));
    expect(Number(first.request.params.get('size'))).toBeLessThanOrEqual(100);
    expect(first.request.params.get('page')).toBe('0');
    expect(first.request.params.has('severity')).toBe(false);
    expect(store.status()).toBe('loading');
    first.flush(page([rawAnomaly()]));
    expect(store.status()).toBe('loaded');

    store.setFilters({ severity: 'HIGH', status: 'open', serviceId: 's-1' });
    const filtered = listRequest(http);
    expect(filtered.request.params.get('severity')).toBe('HIGH');
    expect(filtered.request.params.get('acknowledged')).toBe('false');
    expect(filtered.request.params.get('serviceId')).toBe('s-1');
    filtered.flush(page([]));

    store.setFilters({ status: 'acknowledged', metricId: 'm-1' });
    const metric = listRequest(http);
    expect(metric.request.params.get('acknowledged')).toBe('true');
    expect(metric.request.params.get('metricId')).toBe('m-1');
    // A metric belongs to one service, so the service is not sent next to it.
    expect(metric.request.params.has('serviceId')).toBe(false);
    metric.flush(page([]));
  });

  it('drops rows that are incomplete and orders the rest newest window first', () => {
    const { store, http } = setup();
    store.load();
    listRequest(http).flush(
      page([
        rawAnomaly({ id: 'a', windowStart: '2026-05-01T10:00:00Z' }),
        rawAnomaly({ id: 'b', windowStart: '2026-05-01T12:00:00Z' }),
        rawAnomaly({ id: 'c', serviceId: undefined }),
        rawAnomaly({ id: 'd', windowStart: '2026-05-01T11:00:00Z' }),
      ]),
    );
    expect(store.items().map((item) => item.id)).toEqual(['b', 'd', 'a']);
  });

  it('shows a failure and can try again', () => {
    const { store, http } = setup();
    store.load();
    listRequest(http).flush(
      { type: 'urn:pulseboard:problem:internal-error' },
      { status: 500, statusText: 'x' },
    );
    expect(store.status()).toBe('error');
    expect(store.problem()?.status).toBe(500);

    store.load();
    listRequest(http).flush(page([rawAnomaly()]));
    expect(store.status()).toBe('loaded');
    expect(store.problem()).toBeNull();
  });

  it('cancels the load of old filters when new ones are chosen', () => {
    const { store, http } = setup();
    store.setFilters({ severity: 'LOW' });
    const stale = listRequest(http);
    store.setFilters({ severity: 'HIGH' });
    const fresh = listRequest(http);

    expect(stale.cancelled).toBe(true);
    fresh.flush(page([rawAnomaly({ severity: 'HIGH' })]));
    expect(store.items().map((item) => item.severity)).toEqual(['HIGH']);
  });

  describe('live events', () => {
    function loaded(items: object[] = []) {
      const context = setup();
      context.store.load();
      listRequest(context.http).flush(page(items));
      return context;
    }

    it('adds a new anomaly once, however often it is delivered', () => {
      const { store, bus } = loaded([rawAnomaly({ windowStart: '2026-05-01T09:00:00Z' })]);
      const event = anomaly({ windowStart: '2026-05-01T12:00:00Z' });

      bus.publish(event, 'event');
      bus.publish(event, 'event');
      bus.publish({ ...event }, 'event');

      expect(store.items()).toHaveLength(2);
      expect(store.items()[0].id).toBe(event.id);
      expect(store.totalItems()).toBe(2);
    });

    it('keeps the list ordered by window start, newest first', () => {
      const { store, bus } = loaded();
      bus.publish(anomaly({ id: 'mid', windowStart: '2026-05-01T11:00:00Z' }), 'event');
      bus.publish(anomaly({ id: 'new', windowStart: '2026-05-01T12:00:00Z' }), 'event');
      bus.publish(anomaly({ id: 'old', windowStart: '2026-05-01T10:00:00Z' }), 'event');
      expect(store.items().map((item) => item.id)).toEqual(['new', 'mid', 'old']);
    });

    it('never holds more than the bound, dropping the oldest', () => {
      const { store, bus } = loaded();
      for (let i = 0; i < MAX_ITEMS + 25; i += 1) {
        bus.publish(
          anomaly({
            id: `id-${String(i).padStart(4, '0')}`,
            windowStart: new Date(Date.UTC(2026, 4, 1, 0, 0, i)).toISOString(),
          }),
          'event',
        );
      }
      expect(store.items()).toHaveLength(MAX_ITEMS);
      expect(store.items()[0].id).toBe(`id-${String(MAX_ITEMS + 24).padStart(4, '0')}`);
      expect(store.items().some((item) => item.id === 'id-0000')).toBe(false);
    });

    it('ignores events that do not match the filters', () => {
      const { store, bus, http } = setup();
      store.setFilters({ severity: 'HIGH', status: 'open' });
      listRequest(http).flush(page([]));

      bus.publish(anomaly({ severity: 'MEDIUM' }), 'event');
      bus.publish(anomaly({ severity: 'HIGH', acknowledgedAt: '2026-05-01T12:30:00Z' }), 'event');
      bus.publish(anomaly({ severity: 'HIGH' }), 'event');

      expect(store.items()).toHaveLength(1);
      expect(store.items()[0].severity).toBe('HIGH');
    });

    it('counts events for a page the person is not on instead of moving the list', () => {
      const { store, bus, http } = setup();
      store.setPage(1);
      listRequest(http).flush(page([rawAnomaly()], { totalItems: 30, totalPages: 2 }));
      const before = store.items();

      const event = anomaly({ windowStart: '2026-05-01T13:00:00Z' });
      bus.publish(event, 'event');
      bus.publish(event, 'event');

      expect(store.items()).toBe(before);
      expect(store.pendingIds()).toEqual([event.id]);

      store.showNewest();
      expect(store.page()).toBe(0);
      expect(store.pendingIds()).toEqual([event.id]);
      listRequest(http).flush(page([rawAnomaly()]));
      expect(store.pendingIds()).toEqual([]);
    });

    it('does not take an event for an anomaly it never loaded when the list has not been read yet', () => {
      const { store, bus } = setup();
      bus.publish(anomaly(), 'event');
      expect(store.items()).toHaveLength(0);
    });

    it('does not let a late open copy undo an acknowledgement', () => {
      const open = anomaly({ id: 'x' });
      const { store, bus } = loaded([rawAnomaly({ id: 'x' })]);

      bus.publish({ ...open, acknowledgedAt: '2026-05-01T12:30:00Z', acknowledgedBy: 'u' });
      bus.publish(open, 'event');

      expect(store.items()[0].acknowledgedAt).toBe('2026-05-01T12:30:00Z');
    });

    it('lets a rollback restore the open state', () => {
      const open = anomaly({ id: 'x' });
      const { store, bus } = loaded([rawAnomaly({ id: 'x' })]);

      bus.publish({ ...open, acknowledgedAt: '2026-05-01T12:30:00Z', acknowledgedBy: 'u' });
      bus.publish(open, 'rollback');

      expect(store.items()[0].acknowledgedAt).toBeNull();
    });
  });

  describe('stream connection', () => {
    it('reads the current page again after every (re)connect, without a loading flash', async () => {
      const { store, http, log, stream } = setup();
      store.load();
      listRequest(http).flush(page([rawAnomaly({ id: 'a' })]));
      stream.retain();
      await settle();
      http.expectOne('/api/v1/stream/ticket').flush({ ticket: 't0', expiresInSeconds: 30 });
      await settle();

      log.latest.open();
      const first = listRequest(http);
      expect(store.status()).toBe('loaded');
      // The gap is covered: the server now also knows 'b'.
      first.flush(page([rawAnomaly({ id: 'a' }), rawAnomaly({ id: 'b' })]));
      expect(
        store
          .items()
          .map((item) => item.id)
          .sort(),
      ).toEqual(['a', 'b']);

      log.latest.fail();
      await new Promise((resolve) => setTimeout(resolve, 1100));
      http.expectOne('/api/v1/stream/ticket').flush({ ticket: 't1', expiresInSeconds: 30 });
      await settle();
      log.latest.open();
      listRequest(http).flush(page([rawAnomaly({ id: 'a' })]));
      expect(store.items()).toHaveLength(1);
    });

    it('does not read anything before the list was requested at all', async () => {
      const { http, log, stream } = setup();
      stream.retain();
      await settle();
      http.expectOne('/api/v1/stream/ticket').flush({ ticket: 't0', expiresInSeconds: 30 });
      await settle();
      log.latest.open();
      http.expectNone((req) => req.url === '/api/v1/anomalies');
    });
  });

  describe('logout', () => {
    it('clears the list and ignores an answer that was still on its way', () => {
      const { store, http } = setup();
      store.load();
      const inFlight = listRequest(http);

      TestBed.inject(SessionLifecycle).clear();
      inFlight.flush(page([rawAnomaly()]));

      expect(store.items()).toEqual([]);
      expect(store.status()).toBe('idle');
    });
  });
});
