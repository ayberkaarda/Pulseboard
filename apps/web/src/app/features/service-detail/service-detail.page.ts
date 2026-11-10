import { ChangeDetectionStrategy, Component, computed, effect, inject, input } from '@angular/core';
import { RouterLink } from '@angular/router';

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
 * One service and the metrics it defines. The contract has no "get service" call, so the name comes
 * from the services list. TODO(4b): chart each metric against its expected range.
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
  protected readonly aggregationLabels = AGGREGATION_LABELS;

  protected readonly service = computed(() =>
    this.store.services().items.find((item) => item.id === this.id()),
  );
  protected readonly metrics = computed(
    () => this.store.metrics()[this.id()] ?? { status: 'idle', items: [], problem: null },
  );
  protected readonly summary = computed(() => {
    const environment = this.service()?.environment;
    return environment ? `${environment} environment` : undefined;
  });
  /** The services list is loaded and this id is not in it (or belongs to another tenant). */
  protected readonly missing = computed(
    () => this.store.services().status === 'loaded' && this.service() === undefined,
  );

  constructor() {
    effect(() => {
      const id = this.id();
      this.store.loadMetrics(id);
    });
    if (this.store.services().status === 'idle') {
      this.store.loadServices();
    }
  }

  protected reload(): void {
    this.store.loadServices();
    this.store.loadMetrics(this.id());
  }
}
