import { inject } from '@angular/core';
import { patchState, signalStore, withHooks, withMethods, withState } from '@ngrx/signals';
import { rxMethod } from '@ngrx/signals/rxjs-interop';
import { catchError, from, map, mergeMap, of, pipe, switchMap, tap } from 'rxjs';

import { ApiClient } from '../../api/api-client';
import { MetricDefinition, Service } from '../../api/models';
import { ApiProblem, isApiError } from '../../api/problem';
import { quietContext } from '../../core/http/api-request';
import { SessionLifecycle } from '../../core/session/session-lifecycle';

export type LoadStatus = 'idle' | 'loading' | 'loaded' | 'error';

export interface Loadable<T> {
  readonly status: LoadStatus;
  readonly items: readonly T[];
  readonly problem: ApiProblem | null;
}

interface ServicesState {
  services: Loadable<Service>;
  /** Metric definitions by service id. */
  metrics: Readonly<Record<string, Loadable<MetricDefinition>>>;
  /** One service by id (GET /services/{id}); `items` holds zero or one entry. */
  details: Readonly<Record<string, Loadable<Service>>>;
}

const IDLE = { status: 'idle', items: [], problem: null } as const satisfies Loadable<never>;

const INITIAL: ServicesState = { services: IDLE, metrics: {}, details: {} };

const UNKNOWN_PROBLEM: ApiProblem = {
  status: 0,
  kind: 'unknown',
  title: 'Request failed',
  fieldErrors: [],
};

function problemOf(error: unknown): ApiProblem {
  return isApiError(error) ? error.problem : UNKNOWN_PROBLEM;
}

/** A service whose metric definitions failed to load is asked again no sooner than this. */
export const METRICS_RETRY_MS = 10_000;

/**
 * Services of the tenant and the metric definitions of each service.
 * Each load is a switchMap: starting a load cancels the previous one, so a slow response for a
 * service the person already left can never overwrite the one on screen. Logout resets everything
 * and ignores responses that were still on their way.
 */
export const ServicesStore = signalStore(
  { providedIn: 'root' },
  withState<ServicesState>(INITIAL),
  withMethods((store) => {
    const api = inject(ApiClient);
    // Bumped by reset(); replies from before it are dropped.
    let epoch = 0;
    // Services whose metrics ensureMetrics has asked for and not yet received.
    const requested = new Set<string>();
    /** When the last metadata request of a service failed, epoch ms. */
    const failedAt = new Map<string, number>();

    return {
      // eslint-disable-next-line @typescript-eslint/no-invalid-void-type
      loadServices: rxMethod<void>(
        pipe(
          map(() => epoch),
          tap(() =>
            patchState(store, (state) => ({
              services: { ...state.services, status: 'loading' as const, problem: null },
            })),
          ),
          switchMap((startedIn) =>
            api.get('/api/v1/services', { context: quietContext() }).pipe(
              map((rows): Loadable<Service> => ({
                status: 'loaded',
                items: rows,
                problem: null,
              })),
              catchError((error: unknown) =>
                of<Loadable<Service>>({
                  status: 'error',
                  items: store.services().items,
                  problem: problemOf(error),
                }),
              ),
              tap((services) => {
                if (startedIn === epoch) {
                  patchState(store, { services });
                }
              }),
            ),
          ),
        ),
      ),

      loadService: rxMethod<string>(
        pipe(
          map((serviceId) => ({ serviceId, startedIn: epoch })),
          tap(({ serviceId }) =>
            patchState(store, (state) => ({
              details: {
                ...state.details,
                [serviceId]: {
                  ...(state.details[serviceId] ?? IDLE),
                  status: 'loading' as const,
                  problem: null,
                },
              },
            })),
          ),
          switchMap(({ serviceId, startedIn }) =>
            api
              .get('/api/v1/services/{serviceId}', { path: { serviceId }, context: quietContext() })
              .pipe(
                map((row): Loadable<Service> => ({
                  status: 'loaded',
                  items: [row],
                  problem: null,
                })),
                catchError((error: unknown) =>
                  of<Loadable<Service>>({ status: 'error', items: [], problem: problemOf(error) }),
                ),
                tap((detail) => {
                  if (startedIn === epoch) {
                    patchState(store, (state) => ({
                      details: { ...state.details, [serviceId]: detail },
                    }));
                  }
                }),
              ),
          ),
        ),
      ),

      loadMetrics: rxMethod<string>(
        pipe(
          map((serviceId) => ({ serviceId, startedIn: epoch })),
          tap(({ serviceId }) =>
            patchState(store, (state) => ({
              metrics: {
                ...state.metrics,
                [serviceId]: {
                  ...(state.metrics[serviceId] ?? IDLE),
                  status: 'loading' as const,
                  problem: null,
                },
              },
            })),
          ),
          switchMap(({ serviceId, startedIn }) =>
            api
              .get('/api/v1/services/{serviceId}/metrics', {
                path: { serviceId },
                context: quietContext(),
              })
              .pipe(
                map((rows): Loadable<MetricDefinition> => ({
                  status: 'loaded',
                  items: rows,
                  problem: null,
                })),
                catchError((error: unknown) =>
                  of<Loadable<MetricDefinition>>({
                    status: 'error',
                    items: store.metrics()[serviceId]?.items ?? [],
                    problem: problemOf(error),
                  }),
                ),
                tap((metrics) => {
                  if (startedIn === epoch) {
                    patchState(store, (state) => ({
                      metrics: { ...state.metrics, [serviceId]: metrics },
                    }));
                  }
                }),
              ),
          ),
        ),
      ),

      /**
       * Loads the metric definitions of services that have none loaded or on their way. Unlike
       * loadMetrics these loads do not cancel each other, so a page can ask for the services it
       * shows as they appear. At most 4 requests run at once.
       */
      ensureMetrics(serviceIds: readonly string[]): void {
        const startedIn = epoch;
        const wanted = [...new Set(serviceIds)].filter((id) => {
          if (requested.has(id)) {
            return false;
          }
          const status = (store.metrics()[id] ?? IDLE).status;
          // A failed load is tried again on the next refresh, but not in a tight loop.
          return (
            status === 'idle' ||
            (status === 'error' && Date.now() - (failedAt.get(id) ?? 0) >= METRICS_RETRY_MS)
          );
        });
        for (const id of wanted) {
          requested.add(id);
        }
        from(wanted)
          .pipe(
            mergeMap(
              (serviceId) =>
                api
                  .get('/api/v1/services/{serviceId}/metrics', {
                    path: { serviceId },
                    context: quietContext(),
                  })
                  .pipe(
                    map((rows): Loadable<MetricDefinition> => ({
                      status: 'loaded',
                      items: rows,
                      problem: null,
                    })),
                    catchError((error: unknown) =>
                      of<Loadable<MetricDefinition>>({
                        status: 'error',
                        items: [],
                        problem: problemOf(error),
                      }),
                    ),
                    map((metrics) => ({ serviceId, metrics })),
                  ),
              4,
            ),
          )
          .subscribe(({ serviceId, metrics }) => {
            requested.delete(serviceId);
            if (metrics.status === 'error') {
              failedAt.set(serviceId, Date.now());
            } else {
              failedAt.delete(serviceId);
            }
            if (startedIn === epoch) {
              patchState(store, (state) => ({
                metrics: { ...state.metrics, [serviceId]: metrics },
              }));
            }
          });
      },

      reset(): void {
        epoch += 1;
        requested.clear();
        failedAt.clear();
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
