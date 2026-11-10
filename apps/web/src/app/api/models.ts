import type { components } from './generated/schema';
import type { Severity } from '../shared/signal/signal.model';

type Schemas = components['schemas'];

export type Environment = NonNullable<Schemas['ServiceResponse']['environment']>;
export type Aggregation = NonNullable<Schemas['MetricDefinitionResponse']['aggregation']>;

/** Response shapes as the contract defines them (every always-present field is required there). */
export type Service = Schemas['ServiceResponse'];
export type MetricDefinition = Schemas['MetricDefinitionResponse'];

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

/** Null only when a timestamp cannot be read, which the session cannot work without. */
export function toTokenPair(raw: Schemas['TokenResponse']): TokenPair | null {
  const access = Date.parse(raw.accessTokenExpiresAt);
  const refresh = Date.parse(raw.refreshTokenExpiresAt);
  if (Number.isNaN(access) || Number.isNaN(refresh)) {
    return null;
  }
  return {
    accessToken: raw.accessToken,
    accessTokenExpiresAt: access,
    refreshToken: raw.refreshToken,
    refreshTokenExpiresAt: refresh,
    userId: raw.userId,
    tenantId: raw.tenantId,
    role: raw.role,
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
  /** Name of the metric, so a row never has to look it up. */
  readonly metricName: string;
  readonly serviceId: string;
  readonly serviceName: string;
  readonly severity: Severity;
  readonly detector: Detector;
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

/** An anomaly from a REST response; the contract guarantees its fields. */
export function toAnomaly(raw: AnomalyRaw): Anomaly {
  const startMs = Date.parse(raw.windowStart);
  const endMs = Date.parse(raw.windowEnd);
  return {
    id: raw.id,
    metricId: raw.metricId,
    metricName: raw.metricName,
    serviceId: raw.serviceId,
    serviceName: raw.serviceName,
    severity: raw.severity,
    detector: raw.detector,
    score: raw.score,
    windowStart: raw.windowStart,
    windowEnd: raw.windowEnd,
    startMs,
    endMs: Number.isNaN(endMs) ? startMs : endMs,
    createdAt: raw.createdAt,
    acknowledgedAt: raw.acknowledgedAt,
    acknowledgedBy: raw.acknowledgedBy,
    details: raw.details,
  };
}

function text(value: unknown): value is string {
  return typeof value === 'string' && value.length > 0;
}

/**
 * An anomaly from a stream event. Event data is parsed JSON, not a typed response, so nothing is
 * assumed: null unless every field the UI relies on is present and valid.
 */
export function parseStreamAnomaly(data: unknown): Anomaly | null {
  if (typeof data !== 'object' || data === null) {
    return null;
  }
  const raw = data as Record<string, unknown>;
  if (
    !text(raw['id']) ||
    !text(raw['metricId']) ||
    !text(raw['serviceId']) ||
    !text(raw['metricName']) ||
    !text(raw['serviceName']) ||
    !text(raw['severity']) ||
    !SEVERITIES.includes(raw['severity']) ||
    !text(raw['windowStart']) ||
    Number.isNaN(Date.parse(raw['windowStart'])) ||
    typeof raw['score'] !== 'number' ||
    !Number.isFinite(raw['score'])
  ) {
    return null;
  }
  const start = raw['windowStart'];
  const optionalText = (value: unknown): string | null => (text(value) ? value : null);
  return toAnomaly({
    ...(raw as unknown as AnomalyRaw),
    windowEnd: text(raw['windowEnd']) ? raw['windowEnd'] : start,
    createdAt: text(raw['createdAt']) ? raw['createdAt'] : start,
    detector: (text(raw['detector']) ? raw['detector'] : 'ZSCORE') as AnomalyRaw['detector'],
    acknowledgedAt: optionalText(raw['acknowledgedAt']),
    acknowledgedBy: optionalText(raw['acknowledgedBy']),
    details:
      typeof raw['details'] === 'object' && raw['details'] !== null
        ? (raw['details'] as AnomalyRaw['details'])
        : {},
  });
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
  for (const point of raw.points) {
    const t = Date.parse(point.ts);
    if (Number.isNaN(t)) {
      continue;
    }
    // The bucket value is nullable in the contract: null is a gap, not a number.
    const value =
      typeof point.value === 'number' && Number.isFinite(point.value) ? point.value : null;
    points.push({ t, value, count: point.count });
  }
  points.sort((a, b) => a.t - b.t);
  return {
    aggregation: raw.aggregation,
    stepSeconds: parseDurationSeconds(raw.step),
    points,
  };
}
