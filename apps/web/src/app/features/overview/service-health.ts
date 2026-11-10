import { Anomaly, isAcknowledged } from '../../api/models';
import { HealthState, SEVERITY_RANK, Severity } from '../../shared/signal/signal.model';

/** Health looks at unacknowledged anomalies whose window started within this long ago. */
export const HEALTH_WINDOW_MS = 60 * 60 * 1000;

export interface ServiceHealth {
  readonly state: HealthState;
  /** Unacknowledged anomalies in the last hour. */
  readonly openCount: number;
  readonly maxSeverity: Severity | null;
  /** Window start of the newest of them, epoch ms. */
  readonly lastAnomalyMs: number | null;
  /**
   * The anomaly query was cut off, so the open count is a lower bound and a service without a
   * loaded anomaly cannot be called healthy.
   */
  readonly incomplete: boolean;
}

/** Is this anomaly part of the "unacknowledged, last hour" set at `nowMs`? A window exactly one hour old still counts. */
export function countsForHealth(anomaly: Anomaly, nowMs: number): boolean {
  return !isAcknowledged(anomaly) && anomaly.startMs >= nowMs - HEALTH_WINDOW_MS;
}

/**
 * Health of one service.
 *
 * - any unacknowledged HIGH anomaly in the last hour: down (square)
 * - otherwise any unacknowledged LOW or MEDIUM: degraded (triangle)
 * - otherwise healthy (circle), but only when the service defines metrics
 * - a service with no metrics, or whose metrics are not known, has no data (dashed ring)
 *
 * - when the anomaly data is incomplete (`complete` false) a service with no loaded anomaly is
 *   unknown, never healthy: only a full result can prove the absence of anomalies
 *
 * `metricCount` is null while unknown. An open anomaly proves data exists, so it wins over a
 * missing metric count.
 */
export function deriveHealth(
  anomalies: readonly Anomaly[],
  metricCount: number | null,
  nowMs: number,
  complete = true,
): ServiceHealth {
  let openCount = 0;
  let maxRank = 0;
  let maxSeverity: Severity | null = null;
  let lastAnomalyMs: number | null = null;
  for (const anomaly of anomalies) {
    if (!countsForHealth(anomaly, nowMs)) {
      continue;
    }
    openCount += 1;
    if (SEVERITY_RANK[anomaly.severity] > maxRank) {
      maxRank = SEVERITY_RANK[anomaly.severity];
      maxSeverity = anomaly.severity;
    }
    if (lastAnomalyMs === null || anomaly.startMs > lastAnomalyMs) {
      lastAnomalyMs = anomaly.startMs;
    }
  }
  if (maxSeverity === 'HIGH') {
    return { state: 'down', openCount, maxSeverity, lastAnomalyMs, incomplete: !complete };
  }
  if (maxSeverity !== null) {
    return { state: 'degraded', openCount, maxSeverity, lastAnomalyMs, incomplete: !complete };
  }
  return {
    state: complete && metricCount !== null && metricCount > 0 ? 'healthy' : 'unknown',
    openCount,
    maxSeverity,
    lastAnomalyMs,
    incomplete: !complete,
  };
}

const URGENCY: Readonly<Record<HealthState, number>> = {
  down: 0,
  degraded: 1,
  healthy: 2,
  unknown: 3,
};

/** Down first, then degraded, then by open anomalies, then by name; "no data" last. */
export function compareByUrgency(
  a: { name: string; health: ServiceHealth },
  b: { name: string; health: ServiceHealth },
): number {
  return (
    URGENCY[a.health.state] - URGENCY[b.health.state] ||
    b.health.openCount - a.health.openCount ||
    a.name.localeCompare(b.name)
  );
}
