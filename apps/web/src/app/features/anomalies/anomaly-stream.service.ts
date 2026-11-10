import { DOCUMENT } from '@angular/common';
import { DestroyRef, Injectable, InjectionToken, inject, signal } from '@angular/core';
import { Subject, firstValueFrom } from 'rxjs';

import { ApiClient } from '../../api/api-client';
import { AnomalyRaw, toAnomaly } from '../../api/models';
import { ApiProblem, isApiError } from '../../api/problem';
import { quietContext } from '../../core/http/api-request';
import { SessionLifecycle } from '../../core/session/session-lifecycle';
import { SessionStore } from '../../core/session/session.store';
import { AnomalyBus } from './anomaly-bus';

/** The part of EventSource the stream uses, so tests can fake it. */
export interface EventSourceLike {
  onopen: ((event: Event) => void) | null;
  onerror: ((event: Event) => void) | null;
  addEventListener(type: string, listener: (event: MessageEvent<string>) => void): void;
  close(): void;
}

export type EventSourceFactory = (url: string) => EventSourceLike;

export const EVENT_SOURCE_FACTORY = new InjectionToken<EventSourceFactory>('EVENT_SOURCE_FACTORY', {
  providedIn: 'root',
  factory: () => (url) => {
    if (typeof EventSource === 'undefined') {
      throw new Error('EventSource is not available');
    }
    return new EventSource(url) as unknown as EventSourceLike;
  },
});

/** Source of jitter in [0, 1); replaced in tests for an exact schedule. */
export const STREAM_RANDOM = new InjectionToken<() => number>('STREAM_RANDOM', {
  providedIn: 'root',
  factory: () => Math.random,
});

/**
 * - connecting: first attempt of this session.
 * - live: events are flowing.
 * - reconnecting: the connection dropped or could not be made; another attempt is scheduled.
 * - offline: retrying stopped (the server refused the credentials); a page visit tries again.
 * - paused: closed on purpose because the tab was hidden for a while.
 */
export type StreamStatus = 'idle' | 'connecting' | 'live' | 'reconnecting' | 'offline' | 'paused';

export const BACKOFF_BASE_MS = 1000;
export const BACKOFF_CAP_MS = 30_000;
/** A connection that stayed up this long counts as healthy: the next drop starts from the base delay. */
export const HEALTHY_AFTER_MS = 5000;
/** A hidden tab keeps its stream this long, then lets go (it re-syncs when it becomes visible). */
export const HIDDEN_PAUSE_MS = 5 * 60_000;
/** A page that releases the stream and another that wants it right after do not cause a reconnect. */
export const RELEASE_GRACE_MS = 3000;
/** Longest wait honoured from a Retry-After header. */
const MAX_RETRY_AFTER_MS = 120_000;
/** Consecutive 401 answers to the ticket request before retrying stops. */
export const MAX_AUTH_FAILURES = 3;

/**
 * Delay before reconnect attempt `attempt` (0 based): the base doubles up to the cap, and equal
 * jitter keeps between half and all of it so many tabs do not reconnect in step.
 */
export function backoffDelayMs(attempt: number, random: () => number): number {
  const ceiling = Math.min(BACKOFF_CAP_MS, BACKOFF_BASE_MS * 2 ** Math.min(attempt, 30));
  return Math.round(ceiling * (0.5 + 0.5 * random()));
}

function retryAfterMs(problem: ApiProblem | null): number {
  const seconds =
    problem?.status === 429 || problem?.status === 503 ? problem.retryAfterSeconds : undefined;
  return seconds === undefined ? 0 : Math.min(seconds * 1000, MAX_RETRY_AFTER_MS);
}

/**
 * The live anomaly connection.
 *
 * A browser EventSource cannot send the Authorization header, so every connection first asks
 * POST /api/v1/stream/ticket for a one-time ticket. A ticket is spent by the request that uses it,
 * even when that request is refused, so EVERY attempt asks for a new one and the native
 * auto-reconnect of EventSource is never used (the source is closed on the first error).
 *
 * EventSource hides the HTTP status of a refused connection, so authentication problems surface
 * where the status is visible: the ticket request. A 401 there has already been retried once after a
 * token refresh by the interceptors, so a 401 or 403 that reaches this class is final and ends the
 * retries. A 429 on the ticket request waits for Retry-After; a refused stream (the cap of streams
 * per tenant) shows up as an error before `open` and backs off like any other failure.
 *
 * Events published while disconnected are not replayed: `resynced` fires every time a connection
 * opens so stores re-read GET /api/v1/anomalies.
 */
@Injectable({ providedIn: 'root' })
export class AnomalyStreamService {
  private readonly api = inject(ApiClient);
  private readonly session = inject(SessionStore);
  private readonly bus = inject(AnomalyBus);
  private readonly createSource = inject(EVENT_SOURCE_FACTORY);
  private readonly random = inject(STREAM_RANDOM);
  private readonly document = inject(DOCUMENT);

  private readonly statusState = signal<StreamStatus>('idle');
  private readonly lastEventState = signal<number | null>(null);
  private readonly resyncSubject = new Subject<void>();

  readonly status = this.statusState.asReadonly();
  /** Epoch ms of the last event or connection open, for "last event 14:02". */
  readonly lastEventAt = this.lastEventState.asReadonly();
  /** Emits whenever a connection opens: re-read the list to cover the gap. */
  readonly resynced = this.resyncSubject.asObservable();

  private holders = 0;
  private running = false;
  private generation = 0;
  private attempt = 0;
  private source: EventSourceLike | null = null;
  private openedAt = 0;
  private authFailures = 0;
  private retryTimer: ReturnType<typeof setTimeout> | null = null;
  private hiddenTimer: ReturnType<typeof setTimeout> | null = null;
  private releaseTimer: ReturnType<typeof setTimeout> | null = null;
  private visibilityListener: (() => void) | null = null;

  constructor() {
    inject(SessionLifecycle).onClear(() => this.shutdown());
    inject(DestroyRef).onDestroy(() => this.shutdown());
  }

  /**
   * A page that shows live data calls this and invokes the returned function when it goes away.
   * The connection lives while at least one page holds it, and a short grace period keeps it open
   * across a navigation between two pages.
   */
  retain(): () => void {
    this.holders += 1;
    if (this.releaseTimer !== null) {
      clearTimeout(this.releaseTimer);
      this.releaseTimer = null;
    }
    this.start();
    let released = false;
    return () => {
      if (released) {
        return;
      }
      released = true;
      this.holders = Math.max(0, this.holders - 1);
      if (this.holders === 0) {
        this.releaseTimer = setTimeout(() => {
          this.releaseTimer = null;
          if (this.holders === 0) {
            this.shutdown();
          }
        }, RELEASE_GRACE_MS);
      }
    };
  }

  /** Closes the connection and forgets everything: logout, destroy, nobody needs the stream. */
  shutdown(): void {
    this.generation += 1;
    this.running = false;
    this.closeSource();
    this.clearRetry();
    if (this.releaseTimer !== null) {
      clearTimeout(this.releaseTimer);
      this.releaseTimer = null;
    }
    this.stopListeningForVisibility();
    this.attempt = 0;
    this.authFailures = 0;
    this.lastEventState.set(null);
    this.statusState.set('idle');
  }

  private start(): void {
    if (this.running) {
      if (this.statusState() === 'offline') {
        // Visiting a page after a refusal is a fresh request to connect.
        this.restart();
      }
      return;
    }
    this.running = true;
    this.attempt = 0;
    this.listenForVisibility();
    this.statusState.set('connecting');
    void this.connect(this.generation);
  }

  private restart(): void {
    this.generation += 1;
    this.closeSource();
    this.clearRetry();
    this.attempt = 0;
    this.authFailures = 0;
    this.statusState.set('connecting');
    void this.connect(this.generation);
  }

  private async connect(generation: number): Promise<void> {
    let ticket: string;
    try {
      if ((await this.session.freshAccessToken()) === null) {
        this.halt(generation);
        return;
      }
      if (generation !== this.generation) {
        return;
      }
      const response = await firstValueFrom(
        this.api.post('/api/v1/stream/ticket', { context: quietContext() }),
      );
      if (typeof response.ticket !== 'string' || response.ticket.length === 0) {
        throw new Error('The ticket response is incomplete');
      }
      ticket = response.ticket;
      this.authFailures = 0;
    } catch (error) {
      if (generation !== this.generation) {
        return;
      }
      const problem = isApiError(error) ? error.problem : null;
      if (problem?.status === 403) {
        this.halt(generation);
        return;
      }
      if (problem?.status === 401) {
        // The interceptors already refreshed the token and replayed the request once. Rejected
        // again means the credentials are really refused, but a refresh that could not run (network)
        // looks the same, so give it a few attempts before giving up.
        this.authFailures += 1;
        if (this.authFailures >= MAX_AUTH_FAILURES || !this.session.isAuthenticated()) {
          this.halt(generation);
          return;
        }
      }
      this.scheduleReconnect(generation, retryAfterMs(problem));
      return;
    }
    if (generation !== this.generation) {
      return;
    }
    this.open(generation, ticket);
  }

  private open(generation: number, ticket: string): void {
    let source: EventSourceLike;
    try {
      source = this.createSource(`/api/v1/stream/anomalies?ticket=${encodeURIComponent(ticket)}`);
    } catch {
      this.halt(generation);
      return;
    }
    this.source = source;
    source.onopen = () => {
      if (generation !== this.generation || this.source !== source) {
        return;
      }
      this.openedAt = Date.now();
      this.lastEventState.set(this.openedAt);
      this.statusState.set('live');
      this.resyncSubject.next();
    };
    source.addEventListener('anomaly', (event) => {
      if (generation !== this.generation || this.source !== source) {
        return;
      }
      this.receive(event.data);
    });
    source.onerror = () => {
      if (generation !== this.generation || this.source !== source) {
        return;
      }
      // Closing here stops the browser from reconnecting with the spent ticket.
      source.close();
      this.source = null;
      if (this.openedAt > 0 && Date.now() - this.openedAt >= HEALTHY_AFTER_MS) {
        this.attempt = 0;
      }
      this.openedAt = 0;
      this.scheduleReconnect(generation);
    };
  }

  private receive(data: string): void {
    let parsed: unknown;
    try {
      parsed = JSON.parse(data);
    } catch {
      return;
    }
    if (typeof parsed !== 'object' || parsed === null) {
      return;
    }
    const anomaly = toAnomaly(parsed as AnomalyRaw);
    if (anomaly === null) {
      return;
    }
    this.lastEventState.set(Date.now());
    this.bus.publish(anomaly, 'event');
  }

  private scheduleReconnect(generation: number, minimumMs = 0): void {
    if (generation !== this.generation) {
      return;
    }
    const delay = Math.max(backoffDelayMs(this.attempt, this.random), minimumMs);
    this.attempt += 1;
    this.statusState.set('reconnecting');
    this.clearRetry();
    this.retryTimer = setTimeout(() => {
      this.retryTimer = null;
      void this.connect(generation);
    }, delay);
  }

  /** Retrying cannot help: the credentials were refused or the browser has no EventSource. */
  private halt(generation: number): void {
    if (generation !== this.generation) {
      return;
    }
    this.closeSource();
    this.clearRetry();
    this.statusState.set('offline');
  }

  private closeSource(): void {
    if (this.source !== null) {
      this.source.onopen = null;
      this.source.onerror = null;
      this.source.close();
      this.source = null;
    }
    this.openedAt = 0;
  }

  private clearRetry(): void {
    if (this.retryTimer !== null) {
      clearTimeout(this.retryTimer);
      this.retryTimer = null;
    }
  }

  private listenForVisibility(): void {
    const listener = () => {
      if (this.document.visibilityState === 'hidden') {
        this.hiddenTimer ??= setTimeout(() => {
          this.hiddenTimer = null;
          this.pause();
        }, HIDDEN_PAUSE_MS);
      } else {
        if (this.hiddenTimer !== null) {
          clearTimeout(this.hiddenTimer);
          this.hiddenTimer = null;
        }
        if (this.running && this.statusState() === 'paused') {
          this.restart();
        }
      }
    };
    this.visibilityListener = listener;
    this.document.addEventListener('visibilitychange', listener);
  }

  private stopListeningForVisibility(): void {
    if (this.visibilityListener !== null) {
      this.document.removeEventListener('visibilitychange', this.visibilityListener);
      this.visibilityListener = null;
    }
    if (this.hiddenTimer !== null) {
      clearTimeout(this.hiddenTimer);
      this.hiddenTimer = null;
    }
  }

  private pause(): void {
    if (!this.running) {
      return;
    }
    this.generation += 1;
    this.closeSource();
    this.clearRetry();
    this.statusState.set('paused');
  }
}
