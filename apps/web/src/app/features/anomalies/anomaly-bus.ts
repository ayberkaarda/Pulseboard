import { Injectable } from '@angular/core';
import { Subject } from 'rxjs';

import { Anomaly } from '../../api/models';

/**
 * - event: a newly detected anomaly from the stream.
 * - update: a known anomaly changed (an acknowledgement, optimistic or confirmed).
 * - rollback: restores an earlier state after a failed acknowledgement; it may undo one.
 */
export type AnomalyChangeKind = 'event' | 'update' | 'rollback';

export interface AnomalyChange {
  readonly anomaly: Anomaly;
  readonly kind: AnomalyChangeKind;
}

/**
 * Every place that learns something about an anomaly (the live stream, an acknowledgement, an
 * optimistic update and its rollback) publishes here, and every store that shows anomalies listens.
 */
@Injectable({ providedIn: 'root' })
export class AnomalyBus {
  private readonly subject = new Subject<AnomalyChange>();

  /** An anomaly as it is now. A listener inserts it or replaces what it knows under the same id. */
  readonly changes = this.subject.asObservable();

  publish(anomaly: Anomaly, kind: AnomalyChangeKind = 'update'): void {
    this.subject.next({ anomaly, kind });
  }
}

/**
 * Merges a received anomaly with the copy a store already holds. Acknowledgement only ever moves
 * forward: a late or replayed stream event that still says "open" must not undo an acknowledgement
 * the person just made. A rollback bypasses this.
 */
export function mergeAnomaly(known: Anomaly | undefined, change: AnomalyChange): Anomaly {
  const { anomaly: incoming, kind } = change;
  if (known === undefined || kind === 'rollback') {
    return incoming;
  }
  if (known.acknowledgedAt !== null && incoming.acknowledgedAt === null) {
    return {
      ...incoming,
      acknowledgedAt: known.acknowledgedAt,
      acknowledgedBy: known.acknowledgedBy,
    };
  }
  return incoming;
}
