import { ChangeDetectionStrategy, Component, computed, effect, inject, input } from '@angular/core';
import { RouterLink } from '@angular/router';

import { MetricDefinition, Service } from '../../api/models';
import { LoadErrorComponent } from '../../shared/load-state/load-error.component';
import { SkeletonRowsComponent } from '../../shared/load-state/skeleton-rows.component';
import { PageHeadComponent } from '../../shared/page-head/page-head.component';
import { EmptyStateComponent } from '../../shell/empty-state/empty-state.component';
import { ServicesStore } from '../services/services.store';

const AGGREGATION_LABELS = {
  AVG: 'Average',
  SUM: 'Sum',
  MAX: 'Maximum',
  P95: '95th percentile',
} as const;

/**
 * One service and the metrics it defines. The name and environment come from GET /services/{id}.
 */
@Component({
  selector: 'app-service-detail-page',
  imports: [
    RouterLink,
    EmptyStateComponent,
    LoadErrorComponent,
    PageHeadComponent,
    SkeletonRowsComponent,
  ],
  templateUrl: './service-detail.page.html',
  styleUrl: './service-detail.page.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ServiceDetailPage {
  /** Route parameter, bound through withComponentInputBinding. */
  readonly id = input.required<string>();

  protected readonly store = inject(ServicesStore);

  protected readonly detail = computed(
    () => this.store.details()[this.id()] ?? { status: 'idle', items: [], problem: null },
  );
  protected readonly service = computed<Service | undefined>(() => this.detail().items[0]);
  protected readonly metrics = computed(
    () => this.store.metrics()[this.id()] ?? { status: 'idle', items: [], problem: null },
  );
  protected readonly summary = computed(() => {
    const environment = this.service()?.environment;
    return environment ? `${environment} environment` : undefined;
  });
  /** The server answered 404: no such service in this workspace. */
  protected readonly missing = computed(() => this.detail().problem?.status === 404);

  constructor() {
    effect(() => {
      const id = this.id();
      this.store.loadService(id);
      this.store.loadMetrics(id);
    });
  }

  protected describe(metric: MetricDefinition): string {
    const aggregation = AGGREGATION_LABELS[metric.aggregation];
    return metric.unit ? `${aggregation}, ${metric.unit}` : aggregation;
  }

  protected reload(): void {
    this.store.loadService(this.id());
    this.store.loadMetrics(this.id());
  }
}
