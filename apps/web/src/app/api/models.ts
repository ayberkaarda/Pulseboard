import type { components } from './generated/schema';
import type { Severity } from '../shared/signal/signal.model';

type Schemas = components['schemas'];

export type Environment = NonNullable<Schemas['ServiceResponse']['environment']>;
export type Aggregation = NonNullable<Schemas['MetricDefinitionResponse']['aggregation']>;

/** The contract leaves every response field optional; these are the values the UI requires. */
export interface Service {
  readonly id: string;
  readonly name: string;
  readonly environment: Environment;
  readonly createdAt: string;
}

export interface MetricDefinition {
  readonly id: string;
  readonly serviceId: string;
  readonly name: string;
  readonly unit: string;
  readonly aggregation: Aggregation;
  readonly createdAt: string;
}

export interface TokenPair {
  readonly accessToken: string;
  /** Epoch milliseconds. */
  readonly accessTokenExpiresAt: number;
  readonly refreshToken: string;
  /** Epoch milliseconds. */
  readonly refreshTokenExpiresAt: number;
  readonly userId: string;
  readonly tenantId: string;
  readonly role: string;
}

const ENVIRONMENTS: readonly string[] = ['PROD', 'STAGING', 'DEV'];
const AGGREGATIONS: readonly string[] = ['AVG', 'SUM', 'MAX', 'P95'];

function filled(value: unknown): value is string {
  return typeof value === 'string' && value.length > 0;
}

export function toService(raw: Schemas['ServiceResponse']): Service | null {
  const { id, name, environment, createdAt } = raw;
  return filled(id) && filled(name) && filled(environment) && ENVIRONMENTS.includes(environment)
    ? { id, name, environment, createdAt: createdAt ?? '' }
    : null;
}

export function toMetricDefinition(
  raw: Schemas['MetricDefinitionResponse'],
): MetricDefinition | null {
  const { id, serviceId, name, unit, aggregation, createdAt } = raw;
  return filled(id) &&
    filled(serviceId) &&
    filled(name) &&
    filled(aggregation) &&
    AGGREGATIONS.includes(aggregation)
    ? { id, serviceId, name, unit: unit ?? '', aggregation, createdAt: createdAt ?? '' }
    : null;
}

/** Null when the token response lacks a field the session depends on. */
export function toTokenPair(raw: Schemas['TokenResponse']): TokenPair | null {
  const access = Date.parse(raw.accessTokenExpiresAt ?? '');
  const refresh = Date.parse(raw.refreshTokenExpiresAt ?? '');
  if (
    !filled(raw.accessToken) ||
    !filled(raw.refreshToken) ||
    !filled(raw.userId) ||
    !filled(raw.tenantId) ||
    Number.isNaN(access) ||
    Number.isNaN(refresh)
  ) {
    return null;
  }
  return {
    accessToken: raw.accessToken,
    accessTokenExpiresAt: access,
    refreshToken: raw.refreshToken,
    refreshTokenExpiresAt: refresh,
    userId: raw.userId,
    tenantId: raw.tenantId,
    role: raw.role ?? 'VIEWER',
  };
}

export type AnomalyRaw = Schemas['Anomaly'];
type MetricPointsRaw = Schemas['MetricPoints'];

const SEVERITIES: readonly string[] = ['LOW', 'MEDIUM', 'HIGH'];

export type Detector = 'ZSCORE' | 'ISOLATION_FOREST';

/** A scored window of a metric. Times are kept as the original strings and as epoch milliseconds. */
export interface Anomaly {
  readonly id: string;
  readonly metricId: string;
  readonly serviceId: string;
  readonly severity: Severity;
  readonly detector: string;
  /** Between 0 and 1. */
  readonly score: number;
  readonly windowStart: string;
  readonly windowEnd: string;
  readonly startMs: number;
  readonly endMs: number;
  readonly createdAt: string;
  readonly acknowledgedAt: string | null;
  readonly acknowledgedBy: string | null;
  readonly details: Readonly<Record<string, unknown>>;
}

/** Null when the row lacks a field the UI depends on (the contract leaves every field optional). */
export function toAnomaly(raw: AnomalyRaw): Anomaly | null {
  const { id, metricId, serviceId, severity, detector, score, windowStart, windowEnd } = raw;
  if (
    !filled(id) ||
    !filled(metricId) ||
    !filled(serviceId) ||
    !filled(severity) ||
    !SEVERITIES.includes(severity) ||
    !filled(windowStart) ||
    typeof score !== 'number' ||
    !Number.isFinite(score)
  ) {
    return null;
  }
  const startMs = Date.parse(windowStart);
  const endMs = filled(windowEnd) ? Date.parse(windowEnd) : startMs;
  if (Number.isNaN(startMs) || Number.isNaN(endMs)) {
    return null;
  }
  return {
    id,
    metricId,
    serviceId,
    severity: severity as Severity,
    detector: filled(detector) ? detector : 'UNKNOWN',
    score,
    windowStart,
    windowEnd: filled(windowEnd) ? windowEnd : windowStart,
    startMs,
    endMs,
    createdAt: raw.createdAt ?? windowStart,
    acknowledgedAt: filled(raw.acknowledgedAt) ? raw.acknowledgedAt : null,
    acknowledgedBy: filled(raw.acknowledgedBy) ? raw.acknowledgedBy : null,
    details: raw.details ?? {},
  };
}

export function isAcknowledged(anomaly: Pick<Anomaly, 'acknowledgedAt'>): boolean {
  return anomaly.acknowledgedAt !== null;
}

/** Newest window first, then the larger id, the same order the server uses. */
export function compareAnomalies(a: Anomaly, b: Anomaly): number {
  if (a.startMs !== b.startMs) {
    return b.startMs - a.startMs;
  }
  return a.id < b.id ? 1 : a.id > b.id ? -1 : 0;
}

export interface MetricPoint {
  /** Bucket start, epoch milliseconds. */
  readonly t: number;
  /** Null when the aggregate is not a finite number. */
  readonly value: number | null;
  readonly count: number;
}

export interface MetricSeries {
  readonly aggregation: Aggregation | null;
  readonly stepSeconds: number | null;
  readonly points: readonly MetricPoint[];
}

/** Parses an ISO 8601 duration made of days, hours, minutes and seconds. */
export function parseDurationSeconds(value: string | undefined): number | null {
  const match = /^P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?$/.exec(value ?? '');
  if (!match || value === 'P' || value?.endsWith('T')) {
    return null;
  }
  const [, d, h, m, s] = match;
  return Number(d ?? 0) * 86_400 + Number(h ?? 0) * 3600 + Number(m ?? 0) * 60 + Number(s ?? 0);
}

export function toMetricSeries(raw: MetricPointsRaw): MetricSeries {
  const points: MetricPoint[] = [];
  for (const point of raw.points ?? []) {
    const t = Date.parse(point.ts ?? '');
    if (Number.isNaN(t)) {
      continue;
    }
    const value =
      typeof point.value === 'number' && Number.isFinite(point.value) ? point.value : null;
    points.push({ t, value, count: point.count ?? 0 });
  }
  points.sort((a, b) => a.t - b.t);
  const aggregation = raw.aggregation;
  return {
    aggregation: aggregation && AGGREGATIONS.includes(aggregation) ? aggregation : null,
    stepSeconds: parseDurationSeconds(raw.step),
    points,
  };
}
