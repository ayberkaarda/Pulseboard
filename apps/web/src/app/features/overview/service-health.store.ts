import { computed, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import {
  patchState,
  signalStore,
  withComputed,
  withHooks,
  withMethods,
  withState,
} from '@ngrx/signals';
import { rxMethod } from '@ngrx/signals/rxjs-interop';
import { Observable, catchError, forkJoin, map, of, pipe, switchMap, tap } from 'rxjs';

import { ApiClient } from '../../api/api-client';
import { Anomaly, compareAnomalies, isAcknowledged, toAnomaly } from '../../api/models';
import { ApiProblem, isApiError } from '../../api/problem';
import { quietContext } from '../../core/http/api-request';
import { SessionLifecycle } from '../../core/session/session-lifecycle';
import { Clock } from '../../core/time/clock.service';
import {
  AckLedger,
  AnomalyBus,
  AnomalyChangeKind,
  ChangeBuffer,
  mergeAnomaly,
} from '../anomalies/anomaly-bus';
import { AnomalyStreamService } from '../anomalies/anomaly-stream.service';
import { HEALTH_WINDOW_MS, ServiceHealth, countsForHealth, deriveHealth } from './service-health';

/** One request reads up to 100; a busy hour costs a few more pages, never more than this many in all. */
export const MAX_PAGES = 5;
const PAGE_SIZE = 100;
/** The store never keeps more records than this; the oldest windows are dropped first. */
export const MAX_STORED = 1000;

type HealthStatus = 'idle' | 'loading' | 'loaded' | 'error';

interface HealthState {
  status: HealthStatus;
  /** Unacknowledged anomalies of the last hour, newest first. */
  anomalies: readonly Anomaly[];
  /** More matched than the pages read: counts are lower bounds. */
  truncated: boolean;
  problem: ApiProblem | null;
}

const INITIAL: HealthState = { status: 'idle', anomalies: [], truncated: false, problem: null };

const UNKNOWN_PROBLEM: ApiProblem = {
  status: 0,
  kind: 'unknown',
  title: 'Request failed',
  fieldErrors: [],
};

interface Loaded {
  readonly anomalies: Anomaly[];
  readonly truncated: boolean;
}

/**
 * The anomalies behind the overview tiles: everything unacknowledged that started in the last hour,
 * indexed by service id (the anomaly carries it, so no request per service is needed). One request
 * reads the hour; the live stream and acknowledgements then adjust it, and the clock moving on lets
 * old anomalies age out of the window without a request.
 */
export const ServiceHealthStore = signalStore(
  { providedIn: 'root' },
  withState<HealthState>(INITIAL),
  withComputed((store) => {
    const clock = inject(Clock);
    return {
      /** Anomalies per service id that currently count for health. */
      byService: computed(() => {
        const nowMs = clock.now();
        const index = new Map<string, Anomaly[]>();
        for (const anomaly of store.anomalies()) {
          if (countsForHealth(anomaly, nowMs)) {
            const list = index.get(anomaly.serviceId);
            if (list === undefined) {
              index.set(anomaly.serviceId, [anomaly]);
            } else {
              list.push(anomaly);
            }
          }
        }
        return index;
      }),
    };
  }),
  withMethods((store) => {
    const api = inject(ApiClient);
    const clock = inject(Clock);
    let epoch = 0;
    const ledger = new AckLedger();
    const arrivals = new ChangeBuffer();

    const page = (from: string, index: number) =>
      api.get('/api/v1/anomalies', {
        query: { acknowledged: false, from, page: index, size: PAGE_SIZE },
        context: quietContext(),
      });

    const read = (): Observable<Loaded> => {
      const from = new Date(clock.exactNow() - HEALTH_WINDOW_MS).toISOString();
      return page(from, 0).pipe(
        switchMap((first) => {
          const pages = Math.min(first.totalPages ?? 1, MAX_PAGES);
          const more = Array.from({ length: Math.max(0, pages - 1) }, (_, i) => page(from, i + 1));
          return (more.length === 0 ? of([]) : forkJoin(more)).pipe(
            map((rest) => {
              const rows = [first, ...rest].flatMap((body) => body.items);
              return {
                anomalies: rows.map(toAnomaly),
                truncated: (first.totalPages ?? 1) > MAX_PAGES,
              };
            }),
          );
        }),
      );
    };

    function apply(change: Anomaly, kind: AnomalyChangeKind): void {
      if (store.status() === 'idle') {
        return;
      }
      arrivals.push({ anomaly: change, kind });
      const anomaly = ledger.observe({ anomaly: change, kind });
      const list = store.anomalies();
      const known = list.find((item) => item.id === anomaly.id);
      if (known === undefined && kind === 'update') {
        return;
      }
      const merged = mergeAnomaly(known, { anomaly, kind });
      const rest = list.filter((item) => item.id !== merged.id);
      // Acknowledged anomalies leave the set; a rollback brings one back. Records that aged out of
      // the window are dropped from the store too, and the set has a hard size cap.
      const nowMs = clock.now();
      const kept = (isAcknowledged(merged) ? rest : [...rest, merged])
        .filter((item) => countsForHealth(item, nowMs))
        .sort(compareAnomalies);
      patchState(store, { anomalies: kept.length > MAX_STORED ? kept.slice(0, MAX_STORED) : kept });
    }

    const fetchHealth = rxMethod<{ epoch: number; silent: boolean }>(
      pipe(
        tap(({ silent }) => {
          arrivals.begin();
          if (!silent || store.status() === 'idle') {
            patchState(store, { status: 'loading', problem: null });
          }
        }),
        switchMap(({ epoch: startedIn }) =>
          read().pipe(
            map((loaded): Partial<HealthState> => ({
              status: 'loaded',
              // A snapshot can predate an acknowledgement made while it was in flight.
              anomalies: loaded.anomalies
                .map((item) => ledger.overlay(item))
                .filter((item) => !isAcknowledged(item))
                .sort(compareAnomalies)
                .slice(0, MAX_STORED),
              truncated: loaded.truncated,
              problem: null,
            })),
            catchError((error: unknown) =>
              of<Partial<HealthState>>({
                status: 'error',
                problem: isApiError(error) ? error.problem : UNKNOWN_PROBLEM,
              }),
            ),
            tap((patch) => {
              if (startedIn !== epoch) {
                return;
              }
              patchState(store, patch);
              const arrived = arrivals.end();
              if (patch.status === 'loaded') {
                for (const change of arrived) {
                  apply(change.anomaly, change.kind);
                }
              }
            }),
          ),
        ),
      ),
    );

    return {
      apply,

      load(): void {
        fetchHealth({ epoch, silent: false });
      },

      /** Reads the hour again quietly (after a reconnect). */
      refresh(): void {
        if (store.status() !== 'idle') {
          fetchHealth({ epoch, silent: true });
        }
      },

      healthOf(serviceId: string, metricCount: number | null): ServiceHealth {
        return deriveHealth(
          store.byService().get(serviceId) ?? [],
          metricCount,
          clock.now(),
          !store.truncated(),
        );
      },

      reset(): void {
        epoch += 1;
        ledger.clear();
        arrivals.discard();
        patchState(store, INITIAL);
      },
    };
  }),
  withHooks({
    onInit(store) {
      inject(SessionLifecycle).onClear(() => store.reset());
      inject(AnomalyBus)
        .changes.pipe(takeUntilDestroyed())
        .subscribe(({ anomaly, kind }) => store.apply(anomaly, kind));
      inject(AnomalyStreamService)
        .resynced.pipe(takeUntilDestroyed())
        .subscribe(() => store.refresh());
    },
  }),
);
