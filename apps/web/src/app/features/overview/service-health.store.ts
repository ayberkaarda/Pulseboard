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
import { AnomalyBus, mergeAnomaly } from '../anomalies/anomaly-bus';
import { AnomalyStreamService } from '../anomalies/anomaly-stream.service';
import { HEALTH_WINDOW_MS, ServiceHealth, countsForHealth, deriveHealth } from './service-health';

/** One request reads up to 100; a busy hour costs a few more pages, never more than this many in all. */
export const MAX_PAGES = 5;
const PAGE_SIZE = 100;

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
                anomalies: rows.map(toAnomaly).filter((item) => !isAcknowledged(item)),
                truncated: (first.totalPages ?? 1) > MAX_PAGES,
              };
            }),
          );
        }),
      );
    };

    const fetchHealth = rxMethod<{ epoch: number; silent: boolean }>(
      pipe(
        tap(({ silent }) => {
          if (!silent || store.status() === 'idle') {
            patchState(store, { status: 'loading', problem: null });
          }
        }),
        switchMap(({ epoch: startedIn }) =>
          read().pipe(
            map((loaded): Partial<HealthState> => ({
              status: 'loaded',
              anomalies: [...loaded.anomalies].sort(compareAnomalies),
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
              if (startedIn === epoch) {
                patchState(store, patch);
              }
            }),
          ),
        ),
      ),
    );

    return {
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
        return deriveHealth(store.byService().get(serviceId) ?? [], metricCount, clock.now());
      },

      apply(anomaly: Anomaly, kind: 'event' | 'update' | 'rollback'): void {
        if (store.status() === 'idle') {
          return;
        }
        const list = store.anomalies();
        const known = list.find((item) => item.id === anomaly.id);
        if (known === undefined && kind === 'update') {
          return;
        }
        const merged = mergeAnomaly(known, { anomaly, kind });
        const rest = list.filter((item) => item.id !== merged.id);
        // Acknowledged anomalies leave the set; a rollback brings one back.
        patchState(store, {
          anomalies: isAcknowledged(merged) ? rest : [...rest, merged].sort(compareAnomalies),
        });
      },

      reset(): void {
        epoch += 1;
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
