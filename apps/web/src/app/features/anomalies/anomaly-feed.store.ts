import { inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { patchState, signalStore, withHooks, withMethods, withState } from '@ngrx/signals';
import { rxMethod } from '@ngrx/signals/rxjs-interop';
import { catchError, map, of, pipe, switchMap, tap } from 'rxjs';

import { ApiClient } from '../../api/api-client';
import { Anomaly, compareAnomalies, toAnomaly } from '../../api/models';
import { ApiProblem, isApiError } from '../../api/problem';
import { quietContext } from '../../core/http/api-request';
import { SessionLifecycle } from '../../core/session/session-lifecycle';
import { Severity } from '../../shared/signal/signal.model';
import { AnomalyBus, mergeAnomaly } from './anomaly-bus';
import { AnomalyStreamService } from './anomaly-stream.service';

export type AcknowledgedFilter = 'all' | 'open' | 'acknowledged';

export interface AnomalyFilters {
  readonly severity: Severity | null;
  readonly status: AcknowledgedFilter;
  readonly serviceId: string | null;
  readonly metricId: string | null;
}

export const NO_FILTERS: AnomalyFilters = {
  severity: null,
  status: 'all',
  serviceId: null,
  metricId: null,
};

/** The API accepts 1 to 100 per page. */
export const PAGE_SIZE = 25;
/** The in-memory list never grows past this, however many live events arrive. */
export const MAX_ITEMS = 500;

type FeedStatus = 'idle' | 'loading' | 'loaded' | 'error';

interface FeedState {
  filters: AnomalyFilters;
  /** Zero based. */
  page: number;
  status: FeedStatus;
  items: readonly Anomaly[];
  totalItems: number;
  totalPages: number;
  problem: ApiProblem | null;
  /** Live events that match the filters but belong to a page the person is not looking at. */
  pendingIds: readonly string[];
  /** Live events that matched the filters since the list was last loaded by the person. */
  liveCount: number;
}

const INITIAL: FeedState = {
  filters: NO_FILTERS,
  page: 0,
  status: 'idle',
  items: [],
  totalItems: 0,
  totalPages: 0,
  problem: null,
  pendingIds: [],
  liveCount: 0,
};

const UNKNOWN_PROBLEM: ApiProblem = {
  status: 0,
  kind: 'unknown',
  title: 'Request failed',
  fieldErrors: [],
};

/** Does a (new) anomaly belong in a list that is filtered like this? */
export function matchesFilters(anomaly: Anomaly, filters: AnomalyFilters): boolean {
  return (
    (filters.severity === null || anomaly.severity === filters.severity) &&
    (filters.status === 'all' ||
      (filters.status === 'open') === (anomaly.acknowledgedAt === null)) &&
    (filters.serviceId === null || anomaly.serviceId === filters.serviceId) &&
    (filters.metricId === null || anomaly.metricId === filters.metricId)
  );
}

/** Newest first, one entry per id, at most MAX_ITEMS. */
export function insertAnomaly(list: readonly Anomaly[], anomaly: Anomaly): readonly Anomaly[] {
  const without = list.filter((item) => item.id !== anomaly.id);
  const next = [...without, anomaly].sort(compareAnomalies);
  return next.length > MAX_ITEMS ? next.slice(0, MAX_ITEMS) : next;
}

interface LoadRequest {
  readonly filters: AnomalyFilters;
  readonly page: number;
  readonly epoch: number;
  readonly silent: boolean;
}

/**
 * The anomaly feed: one page of GET /api/v1/anomalies for the current filters, kept up to date by
 * the live stream.
 *
 * - Loads cancel each other (switchMap), so a slow answer for old filters never replaces a newer one.
 * - Live events are merged by id, so a duplicate or a replay changes nothing, ordered newest window
 *   first. On the first page a matching event appears in the list; on later pages it is counted so the
 *   person can jump to the top instead of the list moving under them.
 * - After every (re)connect the current page is read again, because events sent while disconnected
 *   are not replayed.
 * - Acknowledgements arrive through the bus (optimistic, confirmed, rolled back).
 * - Logout clears everything and ignores answers that were still in flight.
 */
export const AnomalyFeedStore = signalStore(
  { providedIn: 'root' },
  withState<FeedState>(INITIAL),
  withMethods((store) => {
    const api = inject(ApiClient);
    let epoch = 0;

    const fetchPage = rxMethod<LoadRequest>(
      pipe(
        tap((request) => {
          if (!request.silent || store.status() === 'idle') {
            patchState(store, { status: 'loading', problem: null });
          }
        }),
        switchMap(({ filters, page, epoch: startedIn }) =>
          api
            .get('/api/v1/anomalies', {
              query: {
                severity: filters.severity ?? undefined,
                acknowledged:
                  filters.status === 'all' ? undefined : filters.status === 'acknowledged',
                serviceId: filters.metricId === null ? (filters.serviceId ?? undefined) : undefined,
                metricId: filters.metricId ?? undefined,
                page,
                size: PAGE_SIZE,
              },
              context: quietContext(),
            })
            .pipe(
              map((body): Partial<FeedState> => ({
                status: 'loaded',
                items: body.items.map(toAnomaly).sort(compareAnomalies),
                totalItems: body.totalItems ?? 0,
                totalPages: body.totalPages ?? 0,
                problem: null,
                pendingIds: [],
              })),
              catchError((error: unknown) =>
                of<Partial<FeedState>>({
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

    const reload = (silent: boolean): void => {
      fetchPage({ filters: store.filters(), page: store.page(), epoch, silent });
    };

    return {
      load(): void {
        patchState(store, { liveCount: 0 });
        reload(false);
      },

      /** Reads the current page again without replacing it by a loading state. */
      refresh(): void {
        if (store.status() !== 'idle') {
          reload(true);
        }
      },

      setFilters(filters: Partial<AnomalyFilters>): void {
        const next = { ...store.filters(), ...filters };
        // A metric belongs to one service; changing the service clears the metric.
        if (filters.serviceId !== undefined && filters.metricId === undefined) {
          patchState(store, { filters: { ...next, metricId: null } });
        } else {
          patchState(store, { filters: next });
        }
        patchState(store, { page: 0, liveCount: 0 });
        reload(false);
      },

      setPage(page: number): void {
        patchState(store, { page: Math.max(0, page) });
        reload(false);
      },

      /** Applies a change from the bus to the list. */
      apply(anomaly: Anomaly, kind: 'event' | 'update' | 'rollback'): void {
        const known = store.items().find((item) => item.id === anomaly.id);
        if (known !== undefined) {
          const merged = mergeAnomaly(known, { anomaly, kind });
          patchState(store, {
            items: store.items().map((item) => (item.id === merged.id ? merged : item)),
          });
          return;
        }
        if (kind !== 'event' || store.status() === 'idle') {
          return;
        }
        if (!matchesFilters(anomaly, store.filters())) {
          return;
        }
        if (store.page() === 0) {
          patchState(store, {
            items: insertAnomaly(store.items(), anomaly),
            totalItems: store.totalItems() + 1,
            liveCount: store.liveCount() + 1,
          });
        } else if (!store.pendingIds().includes(anomaly.id)) {
          patchState(store, {
            pendingIds: [...store.pendingIds(), anomaly.id],
            liveCount: store.liveCount() + 1,
          });
        }
      },

      /** Jumps to the first page, where the pending live events are. */
      showNewest(): void {
        patchState(store, { page: 0 });
        reload(false);
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
