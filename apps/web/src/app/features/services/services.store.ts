import { inject } from '@angular/core';
import { patchState, signalStore, withHooks, withMethods, withState } from '@ngrx/signals';
import { rxMethod } from '@ngrx/signals/rxjs-interop';
import { catchError, map, of, pipe, switchMap, tap } from 'rxjs';

import { ApiClient } from '../../api/api-client';
import { MetricDefinition, Service, toMetricDefinition, toService } from '../../api/models';
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
}

const IDLE = { status: 'idle', items: [], problem: null } as const satisfies Loadable<never>;

const INITIAL: ServicesState = { services: IDLE, metrics: {} };

const UNKNOWN_PROBLEM: ApiProblem = {
  status: 0,
  kind: 'unknown',
  title: 'Request failed',
  fieldErrors: [],
};

function problemOf(error: unknown): ApiProblem {
  return isApiError(error) ? error.problem : UNKNOWN_PROBLEM;
}

function present<T>(items: readonly (T | null)[]): T[] {
  return items.filter((item): item is T => item !== null);
}

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
                items: present(rows.map(toService)),
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
                  items: present(rows.map(toMetricDefinition)),
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
