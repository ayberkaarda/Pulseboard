import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { errorInterceptor } from '../../core/http/interceptors';
import { SessionLifecycle } from '../../core/session/session-lifecycle';
import { SessionStore } from '../../core/session/session.store';
import { AnomalyBus } from './anomaly-bus';
import { SourceLog, fakeSession, rawAnomaly } from './anomaly-testing';
import {
  AnomalyStreamService,
  BACKOFF_CAP_MS,
  EVENT_SOURCE_FACTORY,
  HEALTHY_AFTER_MS,
  HIDDEN_PAUSE_MS,
  MAX_AUTH_FAILURES,
  RELEASE_GRACE_MS,
  STREAM_RANDOM,
  backoffDelayMs,
} from './anomaly-stream.service';

function setup(options: { token?: string | null } = {}) {
  const log = new SourceLog();
  TestBed.configureTestingModule({
    providers: [
      provideHttpClient(withInterceptors([errorInterceptor])),
      provideHttpClientTesting(),
      provideRouter([]),
      { provide: SessionStore, useValue: fakeSession('OWNER', options) },
      { provide: EVENT_SOURCE_FACTORY, useValue: log.factory },
      // Full delay, no randomness: the schedule is exact.
      { provide: STREAM_RANDOM, useValue: () => 1 },
    ],
  });
  return {
    log,
    http: TestBed.inject(HttpTestingController),
    stream: TestBed.inject(AnomalyStreamService),
    bus: TestBed.inject(AnomalyBus),
  };
}

/** Lets promises and zero delay timers run under fake timers. */
async function flushPromises(): Promise<void> {
  await jest.advanceTimersByTimeAsync(0);
}

async function answerTicket(http: HttpTestingController, ticket: string): Promise<void> {
  await flushPromises();
  http.expectOne('/api/v1/stream/ticket').flush({ ticket, expiresInSeconds: 30 });
  await flushPromises();
}

describe('backoffDelayMs', () => {
  it.each([
    [0, 1000],
    [1, 2000],
    [2, 4000],
    [3, 8000],
    [4, 16_000],
    [5, 30_000],
    [6, 30_000],
    [50, 30_000],
  ])('attempt %i waits %i ms at full jitter', (attempt, expected) => {
    expect(backoffDelayMs(attempt, () => 1)).toBe(expected);
  });

  it('keeps between half and all of the delay', () => {
    expect(backoffDelayMs(3, () => 0)).toBe(4000);
    expect(backoffDelayMs(3, () => 0.5)).toBe(6000);
    expect(backoffDelayMs(20, () => 0)).toBe(BACKOFF_CAP_MS / 2);
  });
});

describe('AnomalyStreamService', () => {
  beforeEach(() => jest.useFakeTimers());
  afterEach(() => {
    TestBed.inject(AnomalyStreamService).shutdown();
    jest.useRealTimers();
  });

  it('asks for a new ticket before every connection and never reuses one', async () => {
    const { stream, http, log } = setup();
    stream.retain();

    await answerTicket(http, 'ticket-one');
    expect(log.sources.map((s) => s.url)).toEqual(['/api/v1/stream/anomalies?ticket=ticket-one']);
    log.latest.open();
    expect(stream.status()).toBe('live');

    log.latest.fail();
    expect(stream.status()).toBe('reconnecting');
    await jest.advanceTimersByTimeAsync(1000);
    await answerTicket(http, 'ticket-two');

    expect(log.sources.map((s) => s.url)).toEqual([
      '/api/v1/stream/anomalies?ticket=ticket-one',
      '/api/v1/stream/anomalies?ticket=ticket-two',
    ]);
    // The failed source is closed so the browser cannot reconnect with the spent ticket.
    expect(log.sources[0].closed).toBe(true);
  });

  it('url-encodes the ticket', async () => {
    const { stream, http, log } = setup();
    stream.retain();
    await answerTicket(http, 'a+b/c=');
    expect(log.latest.url).toBe('/api/v1/stream/anomalies?ticket=a%2Bb%2Fc%3D');
  });

  it('backs off 1, 2, 4, 8, 16, 30, 30 seconds while connections keep failing', async () => {
    const { stream, http, log } = setup();
    stream.retain();
    await answerTicket(http, 't0');

    const delays = [1000, 2000, 4000, 8000, 16_000, BACKOFF_CAP_MS, BACKOFF_CAP_MS];
    for (const [index, delay] of delays.entries()) {
      log.latest.fail();
      await jest.advanceTimersByTimeAsync(delay - 1);
      http.expectNone('/api/v1/stream/ticket');
      await jest.advanceTimersByTimeAsync(1);
      await answerTicket(http, `t${index + 1}`);
    }
    expect(log.sources).toHaveLength(delays.length + 1);
  });

  it('starts from the base delay again after a connection that stayed up', async () => {
    const { stream, http, log } = setup();
    stream.retain();
    await answerTicket(http, 't0');
    log.latest.fail();
    await jest.advanceTimersByTimeAsync(1000);
    await answerTicket(http, 't1');
    log.latest.fail();
    await jest.advanceTimersByTimeAsync(2000);
    await answerTicket(http, 't2');

    log.latest.open();
    await jest.advanceTimersByTimeAsync(HEALTHY_AFTER_MS);
    log.latest.fail();

    await jest.advanceTimersByTimeAsync(999);
    http.expectNone('/api/v1/stream/ticket');
    await jest.advanceTimersByTimeAsync(1);
    await answerTicket(http, 't3');
    expect(log.sources).toHaveLength(4);
  });

  it('does not forgive a connection that opens and drops at once', async () => {
    const { stream, http, log } = setup();
    stream.retain();
    await answerTicket(http, 't0');
    log.latest.open();
    log.latest.fail();
    await jest.advanceTimersByTimeAsync(1000);
    await answerTicket(http, 't1');
    log.latest.open();
    log.latest.fail();

    await jest.advanceTimersByTimeAsync(1999);
    http.expectNone('/api/v1/stream/ticket');
    await jest.advanceTimersByTimeAsync(1);
    await answerTicket(http, 't2');
  });

  it('announces every open so stores can re-read the list', async () => {
    const { stream, http, log } = setup();
    const opened = jest.fn();
    stream.resynced.subscribe(opened);
    stream.retain();
    await answerTicket(http, 't0');
    expect(opened).not.toHaveBeenCalled();

    log.latest.open();
    expect(opened).toHaveBeenCalledTimes(1);
    log.latest.fail();
    await jest.advanceTimersByTimeAsync(1000);
    await answerTicket(http, 't1');
    log.latest.open();
    expect(opened).toHaveBeenCalledTimes(2);
  });

  it('publishes valid anomaly events and drops malformed ones', async () => {
    const { stream, http, log, bus } = setup();
    const seen: string[] = [];
    bus.changes.subscribe(({ anomaly, kind }) => seen.push(`${kind}:${anomaly.id}`));
    stream.retain();
    await answerTicket(http, 't0');
    log.latest.open();

    const good = rawAnomaly();
    log.latest.emit(good);
    log.latest.emit('{not json');
    log.latest.emit(rawAnomaly({ severity: 'CRITICAL' as never }));
    log.latest.emit({ id: 'x' });
    log.latest.emit('null');

    expect(seen).toEqual([`event:${good.id}`]);
  });

  it('stops after a 403 for the ticket and does not retry', async () => {
    const { stream, http, log } = setup();
    stream.retain();
    await flushPromises();
    http
      .expectOne('/api/v1/stream/ticket')
      .flush({ type: 'urn:pulseboard:problem:forbidden' }, { status: 403, statusText: 'x' });
    await flushPromises();

    expect(stream.status()).toBe('offline');
    await jest.advanceTimersByTimeAsync(5 * 60_000);
    http.expectNone('/api/v1/stream/ticket');
    expect(log.sources).toHaveLength(0);
  });

  it('gives up after repeated 401 answers to the ticket request', async () => {
    const { stream, http } = setup();
    stream.retain();
    for (let attempt = 0; attempt < MAX_AUTH_FAILURES; attempt += 1) {
      await jest.advanceTimersByTimeAsync(BACKOFF_CAP_MS);
      await flushPromises();
      http
        .expectOne('/api/v1/stream/ticket')
        .flush({ type: 'urn:pulseboard:problem:unauthorized' }, { status: 401, statusText: 'x' });
      await flushPromises();
    }

    expect(stream.status()).toBe('offline');
    await jest.advanceTimersByTimeAsync(5 * 60_000);
    http.expectNone('/api/v1/stream/ticket');
  });

  it('does not start a connection without a session', async () => {
    const { stream, http, log } = setup({ token: null });
    stream.retain();
    await flushPromises();
    expect(stream.status()).toBe('offline');
    http.expectNone('/api/v1/stream/ticket');
    expect(log.sources).toHaveLength(0);
  });

  it('waits for Retry-After when the ticket request is rate limited', async () => {
    const { stream, http } = setup();
    stream.retain();
    await flushPromises();
    http
      .expectOne('/api/v1/stream/ticket')
      .flush(
        { type: 'urn:pulseboard:problem:rate-limited' },
        { status: 429, statusText: 'x', headers: { 'Retry-After': '45' } },
      );
    await flushPromises();
    expect(stream.status()).toBe('reconnecting');

    await jest.advanceTimersByTimeAsync(44_999);
    http.expectNone('/api/v1/stream/ticket');
    await jest.advanceTimersByTimeAsync(1);
    await flushPromises();
    http.expectOne('/api/v1/stream/ticket');
  });

  it('retries a failing ticket request with backoff', async () => {
    const { stream, http } = setup();
    stream.retain();
    await flushPromises();
    http
      .expectOne('/api/v1/stream/ticket')
      .flush({ type: 'urn:pulseboard:problem:internal-error' }, { status: 500, statusText: 'x' });
    await flushPromises();
    await jest.advanceTimersByTimeAsync(1000);
    await flushPromises();
    http.expectOne('/api/v1/stream/ticket');
  });

  it('closes the connection and ignores a late ticket when the session ends', async () => {
    const { stream, http, log } = setup();
    stream.retain();
    await answerTicket(http, 't0');
    log.latest.open();
    const first = log.latest;

    TestBed.inject(SessionLifecycle).clear();

    expect(first.closed).toBe(true);
    expect(stream.status()).toBe('idle');
    first.emit(rawAnomaly());
    await jest.advanceTimersByTimeAsync(60_000);
    http.expectNone('/api/v1/stream/ticket');
  });

  it('opens no connection for a ticket that arrives after logout', async () => {
    const { stream, http, log } = setup();
    stream.retain();
    await flushPromises();
    const pending = http.expectOne('/api/v1/stream/ticket');

    TestBed.inject(SessionLifecycle).clear();
    pending.flush({ ticket: 'late', expiresInSeconds: 30 });
    await flushPromises();

    expect(log.sources).toHaveLength(0);
  });

  it('keeps the connection across a quick hand-over between pages and closes it when nobody is left', async () => {
    const { stream, http, log } = setup();
    const releaseFirst = stream.retain();
    await answerTicket(http, 't0');
    log.latest.open();

    releaseFirst();
    await jest.advanceTimersByTimeAsync(RELEASE_GRACE_MS - 100);
    const releaseSecond = stream.retain();
    await jest.advanceTimersByTimeAsync(RELEASE_GRACE_MS * 2);
    expect(log.latest.closed).toBe(false);
    expect(log.sources).toHaveLength(1);

    releaseSecond();
    await jest.advanceTimersByTimeAsync(RELEASE_GRACE_MS);
    expect(log.latest.closed).toBe(true);
    expect(stream.status()).toBe('idle');
  });

  describe('hidden tab', () => {
    function setVisibility(state: 'hidden' | 'visible'): void {
      Object.defineProperty(document, 'visibilityState', { configurable: true, value: state });
      document.dispatchEvent(new Event('visibilitychange'));
    }
    afterEach(() => setVisibility('visible'));

    it('lets go after a few minutes and reconnects with a new ticket when visible again', async () => {
      const { stream, http, log } = setup();
      stream.retain();
      await answerTicket(http, 't0');
      log.latest.open();

      setVisibility('hidden');
      await jest.advanceTimersByTimeAsync(HIDDEN_PAUSE_MS - 1000);
      expect(stream.status()).toBe('live');
      await jest.advanceTimersByTimeAsync(1000);
      expect(stream.status()).toBe('paused');
      expect(log.sources[0].closed).toBe(true);

      setVisibility('visible');
      await answerTicket(http, 't1');
      expect(log.sources).toHaveLength(2);
      expect(log.latest.url).toContain('ticket=t1');
    });

    it('keeps the connection when the tab comes back in time', async () => {
      const { stream, http, log } = setup();
      stream.retain();
      await answerTicket(http, 't0');
      log.latest.open();

      setVisibility('hidden');
      await jest.advanceTimersByTimeAsync(HIDDEN_PAUSE_MS - 1000);
      setVisibility('visible');
      await jest.advanceTimersByTimeAsync(HIDDEN_PAUSE_MS * 2);

      expect(stream.status()).toBe('live');
      expect(log.sources[0].closed).toBe(false);
    });
  });
});
