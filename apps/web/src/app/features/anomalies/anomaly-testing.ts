import { signal } from '@angular/core';

import { Anomaly, AnomalyRaw, toAnomaly } from '../../api/models';
import { SessionStore } from '../../core/session/session.store';
import { EventSourceLike } from './anomaly-stream.service';

/** Test helpers: anomaly fixtures, a fake EventSource and a fake session. Not part of the app. */

let counter = 0;

export function rawAnomaly(overrides: Partial<AnomalyRaw> = {}): AnomalyRaw {
  counter += 1;
  const n = String(counter).padStart(12, '0');
  return {
    id: `00000000-0000-4000-8000-${n}`,
    tenantId: '00000000-0000-4000-8000-0000000000aa',
    metricId: '22222222-2222-4222-8222-222222222222',
    serviceId: '11111111-1111-4111-8111-111111111111',
    severity: 'MEDIUM',
    detector: 'ZSCORE',
    score: 0.81,
    windowStart: '2026-05-01T12:00:00Z',
    windowEnd: '2026-05-01T12:00:00Z',
    createdAt: '2026-05-01T12:00:05Z',
    acknowledgedAt: null,
    acknowledgedBy: null,
    details: {},
    ...overrides,
  };
}

export function anomaly(overrides: Partial<AnomalyRaw> = {}): Anomaly {
  const parsed = toAnomaly(rawAnomaly(overrides));
  if (parsed === null) {
    throw new Error('Invalid fixture');
  }
  return parsed;
}

export class FakeEventSource implements EventSourceLike {
  onopen: ((event: Event) => void) | null = null;
  onerror: ((event: Event) => void) | null = null;
  closed = false;
  private readonly listeners = new Map<string, ((event: MessageEvent<string>) => void)[]>();

  constructor(readonly url: string) {}

  addEventListener(type: string, listener: (event: MessageEvent<string>) => void): void {
    this.listeners.set(type, [...(this.listeners.get(type) ?? []), listener]);
  }

  close(): void {
    this.closed = true;
  }

  /** The server accepted the connection. */
  open(): void {
    this.onopen?.(new Event('open'));
  }

  /** The connection failed or ended. */
  fail(): void {
    this.onerror?.(new Event('error'));
  }

  emit(data: unknown, type = 'anomaly'): void {
    const text = typeof data === 'string' ? data : JSON.stringify(data);
    for (const listener of this.listeners.get(type) ?? []) {
      listener({ data: text } as MessageEvent<string>);
    }
  }
}

export class SourceLog {
  readonly sources: FakeEventSource[] = [];
  readonly factory = (url: string): FakeEventSource => {
    const source = new FakeEventSource(url);
    this.sources.push(source);
    return source;
  };

  get latest(): FakeEventSource {
    return this.sources[this.sources.length - 1];
  }
}

/** A stand-in for SessionStore with just what the anomaly code reads. */
export function fakeSession(
  role: string | null = 'OWNER',
  options: { token?: string | null } = {},
): Partial<InstanceType<typeof SessionStore>> {
  const token = options.token === undefined ? 'access-token' : options.token;
  const user = signal(role === null ? null : { userId: 'user-1', tenantId: 'tenant-1', role });
  return {
    user: user.asReadonly(),
    isAuthenticated: () => token !== null,
    freshAccessToken: () => Promise.resolve(token),
  } as unknown as Partial<InstanceType<typeof SessionStore>>;
}
