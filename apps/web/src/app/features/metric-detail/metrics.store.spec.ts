import { provideHttpClient, withInterceptors } from '@angular/common/http';
import {
  HttpTestingController,
  TestRequest,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { parseDurationSeconds } from '../../api/models';
import { errorInterceptor } from '../../core/http/interceptors';
import { SessionLifecycle } from '../../core/session/session-lifecycle';
import { AnomalyBus } from '../anomalies/anomaly-bus';
import { SessionStore } from '../../core/session/session.store';
import { AnomalyStreamService, EVENT_SOURCE_FACTORY } from '../anomalies/anomaly-stream.service';
import { SourceLog, anomaly, fakeSession, rawAnomaly } from '../anomalies/anomaly-testing';
import { ANOMALY_LIMIT, MetricsStore } from './metrics.store';
import { MAX_BUCKETS, bucketCount } from './range';

const METRIC = '33333333-3333-4333-8333-333333333333';

function setup() {
  TestBed.configureTestingModule({
    providers: [
      provideHttpClient(withInterceptors([errorInterceptor])),
      provideHttpClientTesting(),
      provideRouter([]),
    ],
  });
  return {
    store: TestBed.inject(MetricsStore),
    http: TestBed.inject(HttpTestingController),
    bus: TestBed.inject(AnomalyBus),
  };
}

const pointsRequest = (http: HttpTestingController): TestRequest =>
  http.expectOne((req) => req.url === `/api/v1/metrics/${METRIC}/points`);
const anomaliesRequest = (http: HttpTestingController): TestRequest =>
  http.expectOne((req) => req.url === '/api/v1/anomalies');

function series(timestamps: string[], aggregation = 'AVG') {
  return {
    metricId: METRIC,
    aggregation,
    step: 'PT5M',
    points: timestamps.map((ts, index) => ({ ts, value: 10 + index, count: 2 })),
  };
}

describe('MetricsStore', () => {
  it('asks for the series and the anomalies of the window together, with a step that fits', () => {
    const { store, http } = setup();
    store.open(METRIC);

    const points = pointsRequest(http);
    const from = Date.parse(points.request.params.get('from') as string);
    const to = Date.parse(points.request.params.get('to') as string);
    const stepSeconds = parseDurationSeconds(points.request.params.get('step') as string);
    expect(to - from).toBe(24 * 3_600_000);
    expect(stepSeconds).toBe(300);
    expect(bucketCount(to - from, stepSeconds as number)).toBeLessThanOrEqual(MAX_BUCKETS);

    const anomalies = anomaliesRequest(http);
    expect(anomalies.request.params.get('metricId')).toBe(METRIC);
    expect(anomalies.request.params.get('from')).toBe(points.request.params.get('from'));
    expect(anomalies.request.params.get('to')).toBe(points.request.params.get('to'));
    expect(anomalies.request.params.get('size')).toBe('100');
    expect(store.series().status).toBe('loading');
    points.flush(series(['2026-05-01T11:00:00Z', '2026-05-01T11:05:00Z']));
    anomalies.flush({ items: [rawAnomaly()], totalItems: 1, totalPages: 1, page: 0, size: 100 });

    expect(store.series().status).toBe('loaded');
    expect(store.series().points.map((p) => p.value)).toEqual([10, 11]);
    expect(store.series().aggregation).toBe('AVG');
    expect(store.anomalies().items).toHaveLength(1);
  });

  it.each([
    ['1h', 3_600_000],
    ['6h', 6 * 3_600_000],
    ['24h', 24 * 3_600_000],
    ['7d', 7 * 24 * 3_600_000],
  ] as const)('the %s preset asks for at most 2000 buckets', (id, expectedRange) => {
    const { store, http } = setup();
    store.open(METRIC);
    pointsRequest(http).flush(series([]));
    anomaliesRequest(http).flush({ items: [] });

    store.selectRange({ kind: 'preset', id });
    const request = pointsRequest(http);
    const range =
      Date.parse(request.request.params.get('to') as string) -
      Date.parse(request.request.params.get('from') as string);
    expect(range).toBe(expectedRange);
    const step = parseDurationSeconds(request.request.params.get('step') as string) as number;
    expect(bucketCount(range, step)).toBeLessThanOrEqual(MAX_BUCKETS);
    anomaliesRequest(http);
  });

  it('cancels the request for an old range when a new one is chosen', () => {
    const { store, http } = setup();
    store.open(METRIC);
    const stalePoints = pointsRequest(http);
    const staleAnomalies = anomaliesRequest(http);

    store.selectRange({ kind: 'preset', id: '1h' });

    expect(stalePoints.cancelled).toBe(true);
    expect(staleAnomalies.cancelled).toBe(true);
    const fresh = pointsRequest(http);
    anomaliesRequest(http).flush({ items: [] });
    fresh.flush(series(['2026-05-01T11:59:00Z']));
    expect(store.series().points).toHaveLength(1);
    expect(store.range()).toEqual({ kind: 'preset', id: '1h' });
  });

  it('does not refetch on its own: opening the same metric again does nothing', () => {
    const { store, http } = setup();
    store.open(METRIC);
    pointsRequest(http).flush(series([]));
    anomaliesRequest(http).flush({ items: [] });

    store.open(METRIC);
    store.open(METRIC);

    http.expectNone((req) => req.url.includes('/points'));
    http.expectNone((req) => req.url === '/api/v1/anomalies');
  });

  it('reads again on refresh, with the same range', () => {
    const { store, http } = setup();
    store.open(METRIC);
    pointsRequest(http).flush(series([]));
    anomaliesRequest(http).flush({ items: [] });
    store.selectRange({ kind: 'preset', id: '6h' });
    pointsRequest(http).flush(series([]));
    anomaliesRequest(http).flush({ items: [] });

    store.refresh();

    const request = pointsRequest(http);
    expect(
      Date.parse(request.request.params.get('to') as string) -
        Date.parse(request.request.params.get('from') as string),
    ).toBe(6 * 3_600_000);
    anomaliesRequest(http);
  });

  it('refuses a custom range the API would reject, without a request', () => {
    const { store, http } = setup();
    store.open(METRIC);
    pointsRequest(http);
    anomaliesRequest(http);

    store.selectRange({ kind: 'custom', fromMs: 2_000, toMs: 1_000 });

    expect(store.window()).toBeNull();
    expect(store.series().status).toBe('idle');
    http.expectNone((req) => req.url.includes('/points'));
  });

  it('keeps the other half when one of the two requests fails', () => {
    const { store, http } = setup();
    store.open(METRIC);
    pointsRequest(http).flush(series(['2026-05-01T11:00:00Z']));
    anomaliesRequest(http).flush(
      { type: 'urn:pulseboard:problem:internal-error' },
      { status: 500, statusText: 'x' },
    );

    expect(store.series().status).toBe('loaded');
    expect(store.anomalies().status).toBe('error');
    expect(store.anomalies().problem?.status).toBe(500);
  });

  it('reports a missing metric as an error with status 404', () => {
    const { store, http } = setup();
    store.open(METRIC);
    pointsRequest(http).flush(
      { type: 'urn:pulseboard:problem:not-found' },
      { status: 404, statusText: 'x' },
    );
    anomaliesRequest(http).flush({ items: [] });

    expect(store.series().status).toBe('error');
    expect(store.series().problem?.status).toBe(404);
  });

  it('ignores an answer that arrives after logout', () => {
    const { store, http } = setup();
    store.open(METRIC);
    const inFlight = pointsRequest(http);
    anomaliesRequest(http);

    TestBed.inject(SessionLifecycle).clear();
    inFlight.flush(series(['2026-05-01T11:00:00Z']));

    expect(store.metricId()).toBeNull();
    expect(store.series().status).toBe('idle');
    expect(store.series().points).toEqual([]);
  });

  it('drops incomplete buckets and keeps empty values as gaps', () => {
    const { store, http } = setup();
    store.open(METRIC);
    pointsRequest(http).flush({
      aggregation: 'MAX',
      step: 'PT1M',
      points: [
        { ts: '2026-05-01T11:01:00Z', value: 2, count: 1 },
        { ts: 'garbage', value: 5, count: 1 },
        { ts: '2026-05-01T11:00:00Z', value: null, count: 1 },
      ],
    });
    anomaliesRequest(http).flush({ items: [] });

    expect(store.series().points.map((p) => p.value)).toEqual([null, 2]);
    expect(store.series().aggregation).toBe('MAX');
  });

  describe('anomalies on the bus', () => {
    function loaded() {
      const context = setup();
      context.store.open(METRIC);
      pointsRequest(context.http).flush(series([]));
      anomaliesRequest(context.http).flush({
        items: [rawAnomaly({ id: 'known', metricId: METRIC })],
      });
      return context;
    }

    it('shows an acknowledgement on the marker', () => {
      const { store, bus } = loaded();
      const open = store.anomalies().items[0];
      bus.publish({ ...open, acknowledgedAt: '2026-05-01T12:30:00Z', acknowledgedBy: 'u' });
      expect(store.anomalies().items[0].acknowledgedAt).toBe('2026-05-01T12:30:00Z');
    });

    it('adds a live event for this metric, but not for another one', () => {
      const { store, bus } = loaded();
      bus.publish(anomaly({ metricId: METRIC, windowStart: new Date().toISOString() }), 'event');
      bus.publish(
        anomaly({ metricId: 'someone-else', windowStart: new Date().toISOString() }),
        'event',
      );
      expect(store.anomalies().items).toHaveLength(2);
      expect(store.anomalies().items.every((item) => item.metricId === METRIC)).toBe(true);
    });

    it('ignores a live event from before the window', () => {
      const { store, bus } = loaded();
      bus.publish(anomaly({ metricId: METRIC, windowStart: '2020-01-01T00:00:00Z' }), 'event');
      expect(store.anomalies().items).toHaveLength(1);
    });
  });
});

describe('MetricsStore against live state', () => {
  const HOUR = 3_600_000;
  const ACK = { acknowledgedAt: '2026-05-01T12:30:00Z', acknowledgedBy: 'u-1' };

  function loadedWith() {
    const context = setup();
    context.store.open(METRIC);
    const points = http_points(context.http);
    return { ...context, points, anomalies: http_anomalies(context.http) };
  }

  function http_points(http: HttpTestingController) {
    return http
      .match((req) => req.url === `/api/v1/metrics/${METRIC}/points`)
      .at(-1) as TestRequest;
  }
  function http_anomalies(http: HttpTestingController) {
    return http.match((req) => req.url === '/api/v1/anomalies').at(-1) as TestRequest;
  }
  const answer = (items: object[]) => ({ items, totalItems: items.length, totalPages: 1 });

  it('keeps events and acknowledgements that happened while the anomalies were loading', () => {
    const { store, bus, points, anomalies } = loadedWith();
    const acked = rawAnomaly({ metricId: METRIC });
    const live = anomaly({ metricId: METRIC, windowStart: new Date().toISOString() });

    bus.publish(live, 'event');
    bus.publish(anomaly({ ...acked, ...ACK }), 'update');
    points.flush(series([]));
    anomalies.flush(answer([acked]));

    expect(store.anomalies().items.map((item) => item.id)).toContain(live.id);
    expect(store.anomalies().items.find((item) => item.id === acked.id)?.acknowledgedAt).toBe(
      ACK.acknowledgedAt,
    );
  });

  it('does not let an older snapshot reopen an acknowledged marker', () => {
    const { store, http, bus, points, anomalies } = loadedWith();
    const raw = rawAnomaly({ metricId: METRIC });
    points.flush(series([]));
    anomalies.flush(answer([raw]));
    bus.publish(anomaly({ ...raw, ...ACK }), 'update');

    store.resync();
    http_anomalies(http).flush(answer([raw]));
    expect(store.anomalies().items[0].acknowledgedAt).toBe(ACK.acknowledgedAt);
  });

  it('reads the anomalies of the displayed window again after a reconnect, without the points', () => {
    const { store, http, points, anomalies } = loadedWith();
    points.flush(series([]));
    anomalies.flush(answer([]));
    const from = store.window()?.fromMs as number;

    store.resync();
    http.expectNone(`/api/v1/metrics/${METRIC}/points`);
    const again = http_anomalies(http);
    expect(Date.parse(again.request.params.get('from') as string)).toBe(from);
    again.flush(answer([rawAnomaly({ metricId: METRIC, windowStart: new Date().toISOString() })]));
    expect(store.anomalies().items).toHaveLength(1);
  });

  it('queues one more read when the reconnect happens while the anomalies are still loading', () => {
    const { store, http, points, anomalies } = loadedWith();
    store.resync();
    points.flush(series([]));
    anomalies.flush(answer([]));
    expect(http_anomalies(http)).toBeDefined();
    expect(store.anomalies().status).toBe('loading');
  });

  it('reads the anomalies again when the live stream reconnects', async () => {
    const log = new SourceLog();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([errorInterceptor])),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: SessionStore, useValue: fakeSession('OWNER') },
        { provide: EVENT_SOURCE_FACTORY, useValue: log.factory },
      ],
    });
    const http = TestBed.inject(HttpTestingController);
    const store = TestBed.inject(MetricsStore);
    const stream = TestBed.inject(AnomalyStreamService);
    store.open(METRIC);
    http_points(http).flush(series([]));
    http_anomalies(http).flush(answer([]));

    stream.retain();
    await new Promise((resolve) => setTimeout(resolve, 0));
    http.expectOne('/api/v1/stream/ticket').flush({ ticket: 't0', expiresInSeconds: 30 });
    await new Promise((resolve) => setTimeout(resolve, 0));
    log.latest.open();

    const again = http_anomalies(http);
    again.flush(answer([rawAnomaly({ metricId: METRIC, windowStart: new Date().toISOString() })]));
    expect(store.anomalies().items).toHaveLength(1);
    stream.shutdown();
  });

  describe('range bounds', () => {
    const FROM = Date.parse('2026-04-01T00:00:00Z');
    const TO = FROM + 6 * HOUR;
    const inside = (ms: number) =>
      anomaly({ metricId: METRIC, windowStart: new Date(ms).toISOString() });

    function customLoaded(existing: object[] = []) {
      const context = setup();
      context.store.open(METRIC);
      context.store.selectRange({ kind: 'custom', fromMs: FROM, toMs: TO });
      const points = context.http
        .match((req) => req.url === `/api/v1/metrics/${METRIC}/points`)
        .at(-1) as TestRequest;
      const anomalies = context.http
        .match((req) => req.url === '/api/v1/anomalies')
        .at(-1) as TestRequest;
      points.flush(series([]));
      anomalies.flush({ items: existing, totalItems: existing.length, totalPages: 1 });
      return context;
    }

    it('rejects events at or after the upper bound of a custom range, and before the lower', () => {
      const { store, bus } = customLoaded();
      bus.publish(inside(TO), 'event');
      bus.publish(inside(TO + HOUR), 'event');
      bus.publish(inside(FROM - 1), 'event');
      expect(store.anomalies().items).toEqual([]);
      expect(store.anomalies().totalItems).toBe(0);

      bus.publish(inside(FROM), 'event');
      bus.publish(inside(TO - 1), 'event');
      expect(store.anomalies().items).toHaveLength(2);
    });

    it('does not evict in-range markers when out-of-range events arrive on a full list', () => {
      const existing = Array.from({ length: ANOMALY_LIMIT }, (_, i) =>
        rawAnomaly({
          metricId: METRIC,
          windowStart: new Date(FROM + i * 60_000).toISOString(),
        }),
      );
      const { store, bus } = customLoaded(existing);
      for (let i = 0; i < 20; i += 1) {
        bus.publish(inside(TO + (i + 1) * HOUR), 'event');
      }
      expect(
        store
          .anomalies()
          .items.map((item) => item.id)
          .sort(),
      ).toEqual(existing.map((item) => (item as { id: string }).id).sort());
    });

    it('leaves a live preset open at the top: an event after the window start is kept', () => {
      const { store, bus, points, anomalies } = loadedWith();
      points.flush(series([]));
      anomalies.flush(answer([]));
      const later = (store.window()?.toMs as number) + 10 * 60_000;
      bus.publish(inside(later), 'event');
      expect(store.anomalies().items).toHaveLength(1);
    });
  });
});
