import { inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { patchState, signalStore, withHooks, withMethods, withState } from '@ngrx/signals';
import { rxMethod } from '@ngrx/signals/rxjs-interop';
import { catchError, map, merge, of, pipe, switchMap, tap } from 'rxjs';

import { ApiClient } from '../../api/api-client';
import {
  Aggregation,
  Anomaly,
  MetricPoint,
  compareAnomalies,
  toAnomaly,
  toMetricSeries,
} from '../../api/models';
import { ApiProblem, isApiError } from '../../api/problem';
import { quietContext } from '../../core/http/api-request';
import { SessionLifecycle } from '../../core/session/session-lifecycle';
import { Clock } from '../../core/time/clock.service';
import { AnomalyBus, mergeAnomaly } from '../anomalies/anomaly-bus';
import { RangeSelection, RangeWindow, isoDuration, resolveWindow } from './range';

type Status = 'idle' | 'loading' | 'loaded' | 'error';

/** The API returns at most 100 anomalies per page; the chart marks the newest of them. */
export const ANOMALY_LIMIT = 100;

interface SeriesState {
  readonly status: Status;
  readonly points: readonly MetricPoint[];
  readonly aggregation: Aggregation | null;
  readonly problem: ApiProblem | null;
}

interface AnomalyState {
  readonly status: Status;
  readonly items: readonly Anomaly[];
  readonly totalItems: number;
  readonly problem: ApiProblem | null;
}

interface MetricsState {
  metricId: string | null;
  range: RangeSelection;
  /** The window of the data on screen (or loading); null when the selection cannot be queried. */
  window: RangeWindow | null;
  series: SeriesState;
  anomalies: AnomalyState;
  /** Epoch ms of the last completed points load. */
  loadedAt: number | null;
}

const IDLE_SERIES: SeriesState = { status: 'idle', points: [], aggregation: null, problem: null };
const IDLE_ANOMALIES: AnomalyState = { status: 'idle', items: [], totalItems: 0, problem: null };

const INITIAL: MetricsState = {
  metricId: null,
  range: { kind: 'preset', id: '24h' },
  window: null,
  series: IDLE_SERIES,
  anomalies: IDLE_ANOMALIES,
  loadedAt: null,
};

const UNKNOWN_PROBLEM: ApiProblem = {
  status: 0,
  kind: 'unknown',
  title: 'Request failed',
  fieldErrors: [],
};

function problemOf(error: unknown): ApiProblem {
  return isApiError(error) ? error.problem : UNKNOWN_PROBLEM;
}

interface Request {
  readonly metricId: string;
  readonly window: RangeWindow;
  readonly epoch: number;
}

/**
 * The points and anomaly markers of one metric for a time range.
 *
 * - The step comes from the range (see chooseStepSeconds), so a query never asks for more than 2000
 *   buckets and the API never has to reject it.
 * - A new request cancels the previous one (switchMap, which also aborts the HTTP calls), so a slow
 *   answer for an old range can never overwrite the one on screen.
 * - Requests are made only on a deliberate action (opening a metric, picking a range, refresh). The
 *   window of a preset is fixed when it is picked, so nothing refetches on its own.
 * - The anomalies of the window and the points load independently; one failing does not hide the other.
 * - Acknowledgements and live events arrive through the bus. Logout clears everything.
 */
export const MetricsStore = signalStore(
  { providedIn: 'root' },
  withState<MetricsState>(INITIAL),
  withMethods((store) => {
    const api = inject(ApiClient);
    const clock = inject(Clock);
    let epoch = 0;

    const fetchWindow = rxMethod<Request>(
      pipe(
        tap(() =>
          patchState(store, (state) => ({
            series: { ...state.series, status: 'loading' as const, problem: null },
            anomalies: { ...state.anomalies, status: 'loading' as const, problem: null },
          })),
        ),
        switchMap(({ metricId, window, epoch: startedIn }) => {
          const from = new Date(window.fromMs).toISOString();
          const to = new Date(window.toMs).toISOString();
          const apply = (patch: Partial<MetricsState>): void => {
            if (startedIn === epoch) {
              patchState(store, patch);
            }
          };

          const points$ = api
            .get('/api/v1/metrics/{metricId}/points', {
              path: { metricId },
              query: { from, to, step: isoDuration(window.stepSeconds) },
              context: quietContext(),
            })
            .pipe(
              map((body): Partial<MetricsState> => {
                const series = toMetricSeries(body);
                return {
                  series: {
                    status: 'loaded',
                    points: series.points,
                    aggregation: series.aggregation,
                    problem: null,
                  },
                  loadedAt: clock.exactNow(),
                };
              }),
              catchError((error: unknown) =>
                of<Partial<MetricsState>>({
                  series: { ...IDLE_SERIES, status: 'error', problem: problemOf(error) },
                }),
              ),
              tap(apply),
            );

          const anomalies$ = api
            .get('/api/v1/anomalies', {
              query: { metricId, from, to, size: ANOMALY_LIMIT },
              context: quietContext(),
            })
            .pipe(
              map((body): Partial<MetricsState> => ({
                anomalies: {
                  status: 'loaded',
                  items: (body.items ?? [])
                    .map(toAnomaly)
                    .filter((item): item is Anomaly => item !== null)
                    .sort(compareAnomalies),
                  totalItems: body.totalItems ?? 0,
                  problem: null,
                },
              })),
              catchError((error: unknown) =>
                of<Partial<MetricsState>>({
                  anomalies: { ...IDLE_ANOMALIES, status: 'error', problem: problemOf(error) },
                }),
              ),
              tap(apply),
            );

          return merge(points$, anomalies$);
        }),
      ),
    );

    function run(metricId: string, range: RangeSelection): void {
      const window = resolveWindow(range, clock.exactNow());
      patchState(store, { metricId, range, window });
      if (window === null) {
        // Nothing to ask for; the page explains the selection. Cancel what is in flight.
        epoch += 1;
        patchState(store, { series: IDLE_SERIES, anomalies: IDLE_ANOMALIES });
        return;
      }
      fetchWindow({ metricId, window, epoch });
    }

    return {
      /** Starts showing a metric with the default range. Opening the metric already on screen does nothing. */
      open(metricId: string): void {
        if (store.metricId() === metricId && store.series().status !== 'error') {
          return;
        }
        epoch += 1;
        patchState(store, { series: IDLE_SERIES, anomalies: IDLE_ANOMALIES, loadedAt: null });
        run(metricId, store.metricId() === metricId ? store.range() : INITIAL.range);
      },

      selectRange(range: RangeSelection): void {
        const metricId = store.metricId();
        if (metricId !== null) {
          run(metricId, range);
        }
      },

      /** Same range, new "now". */
      refresh(): void {
        const metricId = store.metricId();
        if (metricId !== null) {
          run(metricId, store.range());
        }
      },

      /** Applies a change from the bus: acknowledgements, live events of this metric. */
      apply(anomaly: Anomaly, kind: 'event' | 'update' | 'rollback'): void {
        const { items, totalItems, status } = store.anomalies();
        const window = store.window();
        const known = items.find((item) => item.id === anomaly.id);
        if (known !== undefined) {
          const merged = mergeAnomaly(known, { anomaly, kind });
          patchState(store, {
            anomalies: {
              ...store.anomalies(),
              items: items.map((item) => (item.id === merged.id ? merged : item)),
            },
          });
          return;
        }
        if (
          kind !== 'event' ||
          status !== 'loaded' ||
          window === null ||
          anomaly.metricId !== store.metricId() ||
          anomaly.startMs < window.fromMs
        ) {
          return;
        }
        patchState(store, {
          anomalies: {
            ...store.anomalies(),
            items: [...items, anomaly].sort(compareAnomalies).slice(0, ANOMALY_LIMIT),
            totalItems: totalItems + 1,
          },
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
    },
  }),
);
