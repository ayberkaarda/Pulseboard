import { computed, inject } from '@angular/core';
import {
  patchState,
  signalStore,
  withComputed,
  withHooks,
  withMethods,
  withState,
} from '@ngrx/signals';
import { Subject, catchError, map, of, switchMap } from 'rxjs';

import { ApiClient } from '../../api/api-client';
import { ApiProblem, isApiError } from '../../api/problem';
import { quietContext } from '../../core/http/api-request';
import { SessionLifecycle } from '../../core/session/session-lifecycle';
import { AlertEvent, DeliveryStatus, HISTORY_PAGE_SIZE } from './alert-models';

export interface HistoryFilters {
  readonly ruleId: string | null;
  readonly status: DeliveryStatus | null;
  /** Local calendar day, yyyy-MM-dd, first day included. */
  readonly from: string | null;
  /** Local calendar day, yyyy-MM-dd, last day included. */
  readonly to: string | null;
}

export const NO_HISTORY_FILTERS: HistoryFilters = {
  ruleId: null,
  status: null,
  from: null,
  to: null,
};

export type HistoryStatus = 'idle' | 'loading' | 'loaded' | 'error';

interface HistoryState {
  filters: HistoryFilters;
  /** Zero based. */
  page: number;
  status: HistoryStatus;
  items: readonly AlertEvent[];
  totalItems: number;
  totalPages: number;
  problem: ApiProblem | null;
  /** A refresh keeps the rows on screen while it runs. */
  refreshing: boolean;
  /** When the rows on screen were read, epoch ms; null before the first answer. */
  loadedAt: number | null;
}

const INITIAL: HistoryState = {
  filters: NO_HISTORY_FILTERS,
  page: 0,
  status: 'idle',
  items: [],
  totalItems: 0,
  totalPages: 0,
  problem: null,
  refreshing: false,
  loadedAt: null,
};

const UNKNOWN_PROBLEM: ApiProblem = {
  status: 0,
  kind: 'unknown',
  title: 'Request failed',
  fieldErrors: [],
};

const DAY_PATTERN = /^(\d{4})-(\d{2})-(\d{2})$/;

function startOfLocalDay(day: string, plusDays = 0): Date | null {
  const match = DAY_PATTERN.exec(day);
  if (!match) {
    return null;
  }
  const date = new Date(Number(match[1]), Number(match[2]) - 1, Number(match[3]) + plusDays);
  return Number.isNaN(date.getTime()) ? null : date;
}

/** The inclusive `from` and exclusive `to` instants for a pair of local days (both optional). */
export function timeRange(filters: Pick<HistoryFilters, 'from' | 'to'>): {
  from?: string;
  to?: string;
} {
  const from = filters.from ? startOfLocalDay(filters.from) : null;
  const to = filters.to ? startOfLocalDay(filters.to, 1) : null;
  return {
    ...(from ? { from: from.toISOString() } : {}),
    ...(to ? { to: to.toISOString() } : {}),
  };
}

/** Message for an impossible date range, or null. */
export function dateRangeError(filters: Pick<HistoryFilters, 'from' | 'to'>): string | null {
  const from = filters.from ? startOfLocalDay(filters.from) : null;
  const to = filters.to ? startOfLocalDay(filters.to) : null;
  if (from && to && from.getTime() > to.getTime()) {
    return 'The end date must not be before the start date.';
  }
  return null;
}

/**
 * Whether the page should read the history again by itself: only while somebody can see it and
 * something is still waiting for delivery. Nothing is polled otherwise.
 */
export function shouldAutoRefresh(hasPending: boolean, tabVisible: boolean): boolean {
  return hasPending && tabVisible;
}

/**
 * One page of the alert history, filtered on the server. A newer request replaces an older one
 * (switchMap), so a slow answer for filters the person already left never shows; answers that
 * arrive after logout are ignored.
 */
export const AlertHistoryStore = signalStore(
  { providedIn: 'root' },
  withState<HistoryState>(INITIAL),
  withComputed((store) => ({
    hasPending: computed(() => store.items().some((event) => event.deliveryStatus === 'PENDING')),
    rangeError: computed(() => dateRangeError(store.filters())),
    hasFilters: computed(() => {
      const f = store.filters();
      return f.ruleId !== null || f.status !== null || f.from !== null || f.to !== null;
    }),
  })),
  withMethods((store) => {
    const api = inject(ApiClient);
    let epoch = 0;
    const requests = new Subject<{ silent: boolean }>();

    requests
      .pipe(
        map((request) => ({
          ...request,
          startedIn: epoch,
          filters: store.filters(),
          page: store.page(),
        })),
        switchMap((request) =>
          api
            .get('/api/v1/alert-events', {
              query: {
                ruleId: request.filters.ruleId ?? undefined,
                status: request.filters.status ?? undefined,
                ...timeRange(request.filters),
                page: request.page,
                size: HISTORY_PAGE_SIZE,
              },
              context: quietContext(),
            })
            .pipe(
              map((body) => ({ request, body, problem: null as ApiProblem | null })),
              catchError((error: unknown) =>
                of({
                  request,
                  body: null,
                  problem: isApiError(error) ? error.problem : UNKNOWN_PROBLEM,
                }),
              ),
            ),
        ),
      )
      .subscribe(({ request, body, problem }) => {
        if (request.startedIn !== epoch) {
          return;
        }
        if (body === null) {
          patchState(store, {
            status: request.silent && store.items().length > 0 ? 'loaded' : 'error',
            problem: request.silent && store.items().length > 0 ? null : problem,
            refreshing: false,
          });
          return;
        }
        patchState(store, {
          status: 'loaded',
          items: body.items,
          totalItems: body.totalItems,
          totalPages: body.totalPages,
          problem: null,
          refreshing: false,
          loadedAt: Date.now(),
        });
      });

    const load = (silent: boolean): void => {
      if (dateRangeError(store.filters()) !== null) {
        return;
      }
      patchState(store, silent ? { refreshing: true } : { status: 'loading', problem: null });
      requests.next({ silent });
    };

    return {
      /** Reads the current page, showing the loading state. */
      load: (): void => load(false),

      /** Reads the current page again without replacing the rows by a loading state. */
      refresh: (): void => load(true),

      setFilters(change: Partial<HistoryFilters>): void {
        patchState(store, (state) => ({ filters: { ...state.filters, ...change }, page: 0 }));
        load(false);
      },

      clearFilters(): void {
        patchState(store, { filters: NO_HISTORY_FILTERS, page: 0 });
        load(false);
      },

      setPage(page: number): void {
        const last = Math.max(store.totalPages() - 1, 0);
        patchState(store, { page: Math.min(Math.max(page, 0), last) });
        load(false);
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
    },
  }),
);
