export type HealthState = 'healthy' | 'degraded' | 'down' | 'unknown';

export type Severity = 'LOW' | 'MEDIUM' | 'HIGH';

export const HEALTH_LABELS: Readonly<Record<HealthState, string>> = {
  healthy: 'Healthy',
  degraded: 'Degraded',
  down: 'Down',
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
