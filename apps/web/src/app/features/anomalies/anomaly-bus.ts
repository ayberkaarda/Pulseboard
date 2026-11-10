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

/** The most acknowledged ids a store remembers; the oldest are forgotten first. */
export const MAX_TOMBSTONES = 1000;

/**
 * Remembers which anomalies were acknowledged, as seen on the bus. A snapshot read from the server
 * or a late "open" event can predate the acknowledgement; passing it through the ledger keeps the
 * acknowledgement, so an id is never reopened by something older. A rollback is the one thing that
 * lifts a tombstone. The ledger is bounded, the oldest entries drop first.
 */
export class AckLedger {
  private readonly acks = new Map<
    string,
    { acknowledgedAt: string; acknowledgedBy: string | null }
  >();

  /** Records what a change says and returns the anomaly as it should now be known. */
  observe(change: AnomalyChange): Anomaly {
    const { anomaly, kind } = change;
    if (kind === 'rollback') {
      this.acks.delete(anomaly.id);
      return anomaly;
    }
    if (anomaly.acknowledgedAt !== null) {
      this.acks.delete(anomaly.id);
      this.acks.set(anomaly.id, {
        acknowledgedAt: anomaly.acknowledgedAt,
        acknowledgedBy: anomaly.acknowledgedBy,
      });
      while (this.acks.size > MAX_TOMBSTONES) {
        this.acks.delete(this.acks.keys().next().value as string);
      }
      return anomaly;
    }
    return this.overlay(anomaly);
  }

  /** An anomaly from a snapshot, with a remembered acknowledgement applied. Records nothing. */
  overlay(anomaly: Anomaly): Anomaly {
    const tombstone = anomaly.acknowledgedAt === null ? this.acks.get(anomaly.id) : undefined;
    return tombstone === undefined ? anomaly : { ...anomaly, ...tombstone };
  }

  get size(): number {
    return this.acks.size;
  }

  clear(): void {
    this.acks.clear();
  }
}

/** Most changes kept for one request in flight. */
export const MAX_BUFFERED_CHANGES = 200;

/**
 * Collects the changes that arrive while a snapshot request is in flight, so they can be applied
 * again on top of the (possibly older) snapshot once it lands.
 */
export class ChangeBuffer {
  private changes: AnomalyChange[] = [];
  private active = false;

  /** A new request starts; whatever was collected for an earlier one no longer matters. */
  begin(): void {
    this.active = true;
    this.changes = [];
  }

  push(change: AnomalyChange): void {
    if (!this.active) {
      return;
    }
    this.changes.push(change);
    if (this.changes.length > MAX_BUFFERED_CHANGES) {
      this.changes.shift();
    }
  }

  /** Stops collecting and hands back what was collected, oldest first. */
  end(): readonly AnomalyChange[] {
    const collected = this.changes;
    this.discard();
    return collected;
  }

  discard(): void {
    this.active = false;
    this.changes = [];
  }
}
