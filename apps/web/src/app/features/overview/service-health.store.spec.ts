import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { errorInterceptor } from '../../core/http/interceptors';
import { Clock } from '../../core/time/clock.service';
import { SessionStore } from '../../core/session/session.store';
import { AnomalyBus } from '../anomalies/anomaly-bus';
import { EVENT_SOURCE_FACTORY, AnomalyStreamService } from '../anomalies/anomaly-stream.service';
import { SourceLog, anomaly, fakeSession, rawAnomaly } from '../anomalies/anomaly-testing';
import { MAX_STORED, ServiceHealthStore } from './service-health.store';

const time = { ms: Date.now() };

function setup() {
  time.ms = Date.now();
  const log = new SourceLog();
  TestBed.configureTestingModule({
    providers: [
      provideHttpClient(withInterceptors([errorInterceptor])),
      provideHttpClientTesting(),
      provideRouter([]),
      { provide: Clock, useValue: { now: () => time.ms, exactNow: () => time.ms } },
      { provide: SessionStore, useValue: fakeSession('OWNER') },
      { provide: EVENT_SOURCE_FACTORY, useValue: log.factory },
    ],
  });
  return {
    http: TestBed.inject(HttpTestingController),
    store: TestBed.inject(ServiceHealthStore),
    bus: TestBed.inject(AnomalyBus),
  };
}

const recent = (minutes = 5) => new Date(Date.now() - minutes * 60_000).toISOString();
const page = (items: object[]) => ({ items, totalItems: items.length, totalPages: 1 });
const list = (http: HttpTestingController) =>
  http.expectOne((req) => req.url === '/api/v1/anomalies');
const ACK = { acknowledgedAt: new Date().toISOString(), acknowledgedBy: 'u-1' };

describe('ServiceHealthStore', () => {
  afterEach(() => TestBed.inject(AnomalyStreamService).shutdown());

  it('does not reopen an acknowledged id when a late open event or an older snapshot arrives', () => {
    const { store, http, bus } = setup();
    const raw = rawAnomaly({ severity: 'HIGH', windowStart: recent() });
    store.load();
    list(http).flush(page([raw]));
    expect(store.anomalies()).toHaveLength(1);

    bus.publish(anomaly({ ...raw, ...ACK }), 'update');
    expect(store.anomalies()).toHaveLength(0);

    bus.publish(anomaly(raw), 'event');
    expect(store.anomalies()).toHaveLength(0);

    store.refresh();
    list(http).flush(page([raw]));
    expect(store.anomalies()).toHaveLength(0);
  });

  it('brings the anomaly back when the acknowledgement is rolled back', () => {
    const { store, http, bus } = setup();
    const raw = rawAnomaly({ severity: 'HIGH', windowStart: recent() });
    store.load();
    list(http).flush(page([raw]));
    bus.publish(anomaly({ ...raw, ...ACK }), 'update');
    bus.publish(anomaly(raw), 'rollback');
    expect(store.anomalies()).toHaveLength(1);
  });

  it('applies an acknowledgement and a live event that happened while the snapshot was in flight', () => {
    const { store, http, bus } = setup();
    const acked = rawAnomaly({ severity: 'HIGH', windowStart: recent() });
    const live = rawAnomaly({ severity: 'MEDIUM', windowStart: recent(1) });
    store.load();
    const snapshot = list(http);
    bus.publish(anomaly(live), 'event');
    bus.publish(anomaly({ ...acked, ...ACK }), 'update');
    snapshot.flush(page([acked]));

    expect(store.anomalies().map((item) => item.id)).toEqual([live.id]);
  });

  it('ages old records out of the store itself and caps the set', () => {
    const { store, http, bus } = setup();
    const old = rawAnomaly({ windowStart: recent(59) });
    store.load();
    list(http).flush(page([old]));
    expect(store.anomalies()).toHaveLength(1);

    time.ms += 2 * 3_600_000;
    bus.publish(anomaly({ windowStart: new Date(time.ms - 60_000).toISOString() }), 'event');
    expect(store.anomalies()).toHaveLength(1);
    expect(store.anomalies()[0].id).not.toBe(old.id);

    for (let i = 0; i < MAX_STORED + 20; i += 1) {
      bus.publish(anomaly({ windowStart: new Date(time.ms - 60_000).toISOString() }), 'event');
    }
    expect(store.anomalies().length).toBe(MAX_STORED);
  });
});
