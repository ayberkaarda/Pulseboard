export type HealthState = 'healthy' | 'degraded' | 'down' | 'stale' | 'unknown';

export type Severity = 'LOW' | 'MEDIUM' | 'HIGH';

export const HEALTH_LABELS: Readonly<Record<HealthState, string>> = {
  healthy: 'Healthy',
  degraded: 'Degraded',
  down: 'Down',
  stale: 'Stale',
  unknown: 'No data',
};

export const SEVERITY_LABELS: Readonly<Record<Severity, string>> = {
  LOW: 'Low',
  MEDIUM: 'Medium',
  HIGH: 'High',
};

export const SEVERITY_RANK: Readonly<Record<Severity, 1 | 2 | 3>> = {
  LOW: 1,
  MEDIUM: 2,
  HIGH: 3,
};

export const DETECTOR_LABELS: Readonly<Record<string, string>> = {
  ZSCORE: 'Z-score',
  ISOLATION_FOREST: 'Isolation forest',
};

export const AGGREGATION_LABELS: Readonly<Record<string, string>> = {
  AVG: 'Average',
  SUM: 'Sum',
  MAX: 'Maximum',
  P95: '95th percentile',
};
