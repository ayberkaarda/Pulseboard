import { Anomaly } from '../../api/models';
import { anomaly } from '../anomalies/anomaly-testing';
import {
  HEALTH_WINDOW_MS,
  ServiceHealth,
  compareByUrgency,
  countsForHealth,
  STALE_AFTER_MS,
  deriveHealth,
} from './service-health';

const NOW = Date.parse('2026-05-01T12:00:00Z');

function at(msAgo: number, overrides: Parameters<typeof anomaly>[0] = {}): Anomaly {
  return anomaly({ windowStart: new Date(NOW - msAgo).toISOString(), ...overrides });
}

const MINUTE = 60_000;

describe('deriveHealth', () => {
  type Case = [string, Anomaly[], number | null, ServiceHealth['state'], number];
  const cases: Case[] = [
    ['no anomalies, metrics defined: healthy', [], 3, 'healthy', 0],
    ['no anomalies, no metrics: no data', [], 0, 'unknown', 0],
    ['no anomalies, metric count unknown: no data', [], null, 'unknown', 0],
    ['one LOW: degraded', [at(5 * MINUTE, { severity: 'LOW' })], 2, 'degraded', 1],
    ['one MEDIUM: degraded', [at(5 * MINUTE, { severity: 'MEDIUM' })], 2, 'degraded', 1],
    ['one HIGH: down', [at(5 * MINUTE, { severity: 'HIGH' })], 2, 'down', 1],
    [
      'mixed severities: the worst wins',
      [
        at(5 * MINUTE, { severity: 'LOW' }),
        at(6 * MINUTE, { severity: 'HIGH' }),
        at(7 * MINUTE, { severity: 'MEDIUM' }),
      ],
      2,
      'down',
      3,
    ],
    [
      'acknowledged anomalies are ignored',
      [at(5 * MINUTE, { severity: 'HIGH', acknowledgedAt: '2026-05-01T11:58:00Z' })],
      2,
      'healthy',
      0,
    ],
    [
      'an acknowledged HIGH does not hide an open MEDIUM',
      [
        at(5 * MINUTE, { severity: 'HIGH', acknowledgedAt: '2026-05-01T11:58:00Z' }),
        at(6 * MINUTE, { severity: 'MEDIUM' }),
      ],
      2,
      'degraded',
      1,
    ],
    [
      'exactly one hour old still counts',
      [at(HEALTH_WINDOW_MS, { severity: 'HIGH' })],
      2,
      'down',
      1,
    ],
    [
      'one millisecond past the hour no longer counts',
      [at(HEALTH_WINDOW_MS + 1, { severity: 'HIGH' })],
      2,
      'healthy',
      0,
    ],
    [
      'old anomaly, no metrics known: no data',
      [at(2 * HEALTH_WINDOW_MS, { severity: 'HIGH' })],
      0,
      'unknown',
      0,
    ],
    [
      'an open anomaly proves data: degraded even without a metric count',
      [at(MINUTE, { severity: 'LOW' })],
      null,
      'degraded',
      1,
    ],
    [
      'a window in the future (clock skew) counts',
      [at(-5 * MINUTE, { severity: 'HIGH' })],
      1,
      'down',
      1,
    ],
  ];

  it.each(cases)('%s', (_name, anomalies, metricCount, state, openCount) => {
    const health = deriveHealth(anomalies, metricCount, NOW);
    expect(health.state).toBe(state);
    expect(health.openCount).toBe(openCount);
  });

  it('reports the highest severity and the newest window', () => {
    const health = deriveHealth(
      [
        at(30 * MINUTE, { severity: 'HIGH' }),
        at(10 * MINUTE, { severity: 'LOW' }),
        at(20 * MINUTE, { severity: 'MEDIUM' }),
      ],
      1,
      NOW,
    );
    expect(health.maxSeverity).toBe('HIGH');
    expect(health.lastAnomalyMs).toBe(NOW - 10 * MINUTE);
  });

  it('has no severity or time when nothing counts', () => {
    const health = deriveHealth([], 1, NOW);
    expect(health.maxSeverity).toBeNull();
    expect(health.lastAnomalyMs).toBeNull();
  });
});

describe('countsForHealth', () => {
  it('is the same rule as the derivation uses', () => {
    expect(countsForHealth(at(HEALTH_WINDOW_MS), NOW)).toBe(true);
    expect(countsForHealth(at(HEALTH_WINDOW_MS + 1), NOW)).toBe(false);
    expect(countsForHealth(at(1, { acknowledgedAt: '2026-05-01T11:59:00Z' }), NOW)).toBe(false);
  });
});

describe('compareByUrgency', () => {
  const make = (name: string, state: ServiceHealth['state'], openCount = 0) => ({
    name,
    health: {
      state,
      openCount,
      maxSeverity: null,
      lastAnomalyMs: null,
      incomplete: false,
      lastPointMs: undefined,
    },
  });

  it('puts down first, then degraded, then healthy, then no data; ties by open count then name', () => {
    const list = [
      make('zeta', 'unknown'),
      make('alpha', 'healthy'),
      make('beta', 'degraded', 1),
      make('gamma', 'down', 1),
      make('delta', 'degraded', 4),
      make('able', 'down', 1),
    ];
    expect(list.sort(compareByUrgency).map((item) => item.name)).toEqual([
      'able',
      'gamma',
      'delta',
      'beta',
      'alpha',
      'zeta',
    ]);
  });
});

describe('deriveHealth with an incomplete anomaly result', () => {
  it('never says healthy: a service without a loaded anomaly is unknown', () => {
    const health = deriveHealth([], 3, NOW, false);
    expect(health.state).toBe('unknown');
    expect(health.incomplete).toBe(true);
  });

  it('keeps what was proven: a loaded HIGH is still down', () => {
    const health = deriveHealth([at(5 * MINUTE, { severity: 'HIGH' })], 3, NOW, false);
    expect(health.state).toBe('down');
    expect(health.incomplete).toBe(true);
  });

  it('is healthy again once the result is complete', () => {
    expect(deriveHealth([], 3, NOW, true).state).toBe('healthy');
  });
});

describe('deriveHealth with the age of the newest sample', () => {
  it('is stale, not healthy, when the newest sample is older than the threshold', () => {
    const health = deriveHealth([], 3, NOW, true, NOW - STALE_AFTER_MS - 1);
    expect(health.state).toBe('stale');
    expect(health.lastPointMs).toBe(NOW - STALE_AFTER_MS - 1);
  });

  it('is healthy at exactly the threshold and with fresh data', () => {
    expect(deriveHealth([], 3, NOW, true, NOW - STALE_AFTER_MS).state).toBe('healthy');
    expect(deriveHealth([], 3, NOW, true, NOW - 1000).state).toBe('healthy');
  });

  it('has no data when the service never had a sample', () => {
    expect(deriveHealth([], 3, NOW, true, null).state).toBe('unknown');
  });

  it('does not judge by age when it is not known', () => {
    expect(deriveHealth([], 3, NOW, true, undefined).state).toBe('healthy');
  });

  it('lets an open anomaly win over staleness', () => {
    const old = NOW - 3 * 3_600_000;
    expect(deriveHealth([at(5 * MINUTE, { severity: 'HIGH' })], 3, NOW, true, old).state).toBe(
      'down',
    );
    expect(deriveHealth([at(5 * MINUTE, { severity: 'LOW' })], 3, NOW, true, old).state).toBe(
      'degraded',
    );
  });

  it('sorts stale after degraded and before healthy', () => {
    const make = (name: string, state: ServiceHealth['state']) => ({
      name,
      health: {
        state,
        openCount: 0,
        maxSeverity: null,
        lastAnomalyMs: null,
        incomplete: false,
        lastPointMs: undefined,
      },
    });
    const list = [make('a', 'healthy'), make('b', 'stale'), make('c', 'degraded')];
    expect(list.sort(compareByUrgency).map((item) => item.name)).toEqual(['c', 'b', 'a']);
  });
});
