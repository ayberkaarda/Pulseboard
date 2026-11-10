import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../api/api-client';
import { Anomaly, toAnomaly } from '../../api/models';
import { describeProblem, isApiError } from '../../api/problem';
import { quietContext } from '../../core/http/api-request';
import { Notifier } from '../../core/notify/notifier.service';
import { SessionStore } from '../../core/session/session.store';
import { AnomalyBus } from './anomaly-bus';

const ROLES_THAT_ACKNOWLEDGE: readonly string[] = ['OWNER', 'ADMIN'];

/**
 * Acknowledging an anomaly. The change shows at once (optimistic), the server's answer replaces it,
 * and a failure puts the previous state back and says why. Every store hears about all three steps
 * through the bus, so the feed, the overview and the metric page agree.
 */
@Injectable({ providedIn: 'root' })
export class AnomalyActions {
  private readonly api = inject(ApiClient);
  private readonly bus = inject(AnomalyBus);
  private readonly session = inject(SessionStore);
  private readonly notifier = inject(Notifier);

  private readonly pendingState = signal<ReadonlySet<string>>(new Set());

  /** Ids with an acknowledgement on its way. */
  readonly pending = this.pendingState.asReadonly();

  /** The API allows OWNER and ADMIN only; everyone else gets a disabled control with a reason. */
  readonly canAcknowledge = computed(() =>
    ROLES_THAT_ACKNOWLEDGE.includes(this.session.user()?.role ?? ''),
  );

  /** Resolves true when the server confirmed the acknowledgement. */
  async acknowledge(anomaly: Anomaly): Promise<boolean> {
    if (
      !this.canAcknowledge() ||
      anomaly.acknowledgedAt !== null ||
      this.pending().has(anomaly.id)
    ) {
      return false;
    }
    this.setPending(anomaly.id, true);
    this.bus.publish({
      ...anomaly,
      acknowledgedAt: new Date().toISOString(),
      acknowledgedBy: this.session.user()?.userId ?? null,
    });
    try {
      const response = await firstValueFrom(
        this.api.post('/api/v1/anomalies/{id}/ack', {
          path: { id: anomaly.id },
          context: quietContext(),
        }),
      );
      const confirmed = toAnomaly(response);
      if (confirmed === null) {
        throw new Error('The acknowledgement response is incomplete');
      }
      this.bus.publish(confirmed);
      return true;
    } catch (error) {
      this.bus.publish(anomaly, 'rollback');
      void this.notifier.error(this.explain(error));
      return false;
    } finally {
      this.setPending(anomaly.id, false);
    }
  }

  private explain(error: unknown): string {
    if (isApiError(error)) {
      switch (error.problem.status) {
        case 403:
          return 'Only owners and admins can acknowledge anomalies.';
        case 404:
          return 'That anomaly no longer exists. Reload the list.';
        default:
          return describeProblem(error.problem);
      }
    }
    return 'The acknowledgement could not be saved. Try again.';
  }

  private setPending(id: string, on: boolean): void {
    const next = new Set(this.pending());
    if (on) {
      next.add(id);
    } else {
      next.delete(id);
    }
    this.pendingState.set(next);
  }
}
