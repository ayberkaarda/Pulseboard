import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  effect,
  inject,
  input,
  signal,
  untracked,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { RouterLink } from '@angular/router';
import { format } from 'date-fns';

import { MetricDefinition } from '../../api/models';
import { absoluteTime, formatValue } from '../../shared/format/format-time';
import { LoadErrorComponent } from '../../shared/load-state/load-error.component';
import { SkeletonRowsComponent } from '../../shared/load-state/skeleton-rows.component';
import { PageHeadComponent } from '../../shared/page-head/page-head.component';
import { AGGREGATION_LABELS } from '../../shared/signal/signal.model';
import { EmptyStateComponent } from '../../shell/empty-state/empty-state.component';
import { AnomalyActions } from '../anomalies/anomaly-actions.service';
import { AnomalyRowComponent } from '../anomalies/anomaly-row.component';
import { AnomalyStreamService } from '../anomalies/anomaly-stream.service';
import { ServicesStore } from '../services/services.store';
import { countGaps, summarizeSeries } from './chart-options';
import { MetricChartComponent } from './metric-chart.component';
import { MetricsStore, ANOMALY_LIMIT } from './metrics.store';
import { RANGE_PRESETS, RangePresetId, formatStep, resolveWindow } from './range';

/** `datetime-local` value ("2026-05-01T14:30") for an epoch time, in the viewer's zone. */
function toLocalInput(ms: number): string {
  return format(ms, "yyyy-MM-dd'T'HH:mm");
}

/**
 * One metric over time: range presets and a custom range, the series with anomaly markers, the same
 * numbers as text and as a table, and the anomalies of the range with their acknowledge action.
 */
@Component({
  selector: 'app-metric-detail-page',
  imports: [
    MatButtonModule,
    RouterLink,
    AnomalyRowComponent,
    EmptyStateComponent,
    LoadErrorComponent,
    MetricChartComponent,
    PageHeadComponent,
    SkeletonRowsComponent,
  ],
  templateUrl: './metric-detail.page.html',
  styleUrl: './metric-detail.page.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MetricDetailPage {
  /** Route parameter, bound through withComponentInputBinding. */
  readonly id = input.required<string>();

  protected readonly store = inject(MetricsStore);
  protected readonly catalog = inject(ServicesStore);
  protected readonly actions = inject(AnomalyActions);

  protected readonly presets = RANGE_PRESETS;
  protected readonly anomalyLimit = ANOMALY_LIMIT;
  protected readonly showTable = signal(false);
  protected readonly customOpen = signal(false);
  protected readonly customError = signal<string | null>(null);
  protected readonly customFrom = signal('');
  protected readonly customTo = signal('');

  protected readonly metric = computed<MetricDefinition | undefined>(
    () => this.store.definition().item ?? undefined,
  );
  protected readonly serviceName = computed(() => {
    const serviceId = this.metric()?.serviceId;
    return this.catalog.services().items.find((item) => item.id === serviceId)?.name;
  });
  protected readonly serviceId = computed(() => this.metric()?.serviceId);

  protected readonly title = computed(() => this.metric()?.name ?? 'Metric');
  protected readonly unit = computed(() => this.metric()?.unit ?? '');
  protected readonly aggregation = computed(
    () => this.store.series().aggregation ?? this.metric()?.aggregation ?? null,
  );
  protected readonly summaryLine = computed(() => {
    const aggregation = this.aggregation();
    const parts = [
      this.serviceName(),
      aggregation ? AGGREGATION_LABELS[aggregation] : undefined,
      this.unit() || undefined,
    ].filter((part): part is string => part !== undefined);
    return parts.length > 0
      ? parts.join(', ')
      : 'Values over time, with the anomalies the detectors found.';
  });

  protected readonly stats = computed(() => summarizeSeries(this.store.series().points));
  protected readonly gaps = computed(() => {
    const window = this.store.window();
    return window === null ? 0 : countGaps(this.store.series().points, window.stepSeconds);
  });
  protected readonly stepLabel = computed(() => {
    const window = this.store.window();
    return window === null ? '' : formatStep(window.stepSeconds);
  });
  protected readonly loadedAtLabel = computed(() => {
    const at = this.store.loadedAt();
    return at === null ? '' : format(at, 'HH:mm:ss');
  });
  protected readonly notFound = computed(
    () =>
      this.store.definition().problem?.status === 404 ||
      this.store.series().problem?.status === 404,
  );
  protected readonly truncated = computed(
    () => this.store.anomalies().totalItems > this.store.anomalies().items.length,
  );

  constructor() {
    const release = inject(AnomalyStreamService).retain();
    inject(DestroyRef).onDestroy(() => {
      release();
      // Coming back later starts from fresh data, not from what was on screen last time.
      this.store.reset();
    });

    effect(() => {
      const id = this.id();
      untracked(() => {
        this.store.open(id);
        this.showTable.set(false);
      });
    });

    // The owning service's name comes from the services list; the metric itself is read by id.
    this.catalog.loadServices();
  }

  protected isPreset(id: RangePresetId): boolean {
    const range = this.store.range();
    return range.kind === 'preset' && range.id === id;
  }

  protected selectPreset(id: RangePresetId): void {
    this.customOpen.set(false);
    this.customError.set(null);
    this.store.selectRange({ kind: 'preset', id });
  }

  protected openCustom(): void {
    const window = this.store.window();
    const now = Date.now();
    this.customFrom.set(toLocalInput(window?.fromMs ?? now - 3_600_000));
    this.customTo.set(toLocalInput(window?.toMs ?? now));
    this.customOpen.set(true);
  }

  protected applyCustom(): void {
    const fromMs = Date.parse(this.customFrom());
    const toMs = Date.parse(this.customTo());
    if (Number.isNaN(fromMs) || Number.isNaN(toMs)) {
      this.customError.set('Enter both a start and an end.');
      return;
    }
    const range = { kind: 'custom', fromMs, toMs } as const;
    if (resolveWindow(range, Date.now()) === null) {
      this.customError.set('The start must be before the end, at most 366 days apart.');
      return;
    }
    this.customError.set(null);
    this.store.selectRange(range);
  }

  protected value(value: number | null): string {
    return value === null ? 'no value' : formatValue(value);
  }

  protected exact(ms: number): string {
    return absoluteTime(ms);
  }
}
