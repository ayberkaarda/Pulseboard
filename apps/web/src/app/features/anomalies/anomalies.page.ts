import {
  DestroyRef,
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';

import { Severity } from '../../shared/signal/signal.model';
import { LoadErrorComponent } from '../../shared/load-state/load-error.component';
import { SkeletonRowsComponent } from '../../shared/load-state/skeleton-rows.component';
import { PageHeadComponent } from '../../shared/page-head/page-head.component';
import { EmptyStateComponent } from '../../shell/empty-state/empty-state.component';
import { ServicesStore } from '../services/services.store';
import { AnomalyActions } from './anomaly-actions.service';
import { AcknowledgedFilter, AnomalyFeedStore, NO_FILTERS } from './anomaly-feed.store';
import { AnomalyRowComponent } from './anomaly-row.component';
import { AnomalyStreamService } from './anomaly-stream.service';
import { ConnectionStatusComponent } from './connection-status.component';
import { LiveAnnouncerComponent } from './live-announcer.component';

/**
 * The anomaly feed: filters, one page of anomalies (newest first), live additions on the first page,
 * acknowledge. Names of services and metrics come from the services store, which loads the metric
 * definitions of the services that actually appear in the list (not of every service).
 */
@Component({
  selector: 'app-anomalies-page',
  imports: [
    MatButtonModule,
    AnomalyRowComponent,
    ConnectionStatusComponent,
    EmptyStateComponent,
    LiveAnnouncerComponent,
    LoadErrorComponent,
    PageHeadComponent,
    SkeletonRowsComponent,
  ],
  templateUrl: './anomalies.page.html',
  styleUrl: './anomalies.page.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AnomaliesPage {
  protected readonly feed = inject(AnomalyFeedStore);
  protected readonly catalog = inject(ServicesStore);
  protected readonly actions = inject(AnomalyActions);

  protected readonly noFilters = NO_FILTERS;

  protected readonly hasFilters = computed(() => {
    const f = this.feed.filters();
    return f.severity !== null || f.status !== 'all' || f.serviceId !== null || f.metricId !== null;
  });

  /** Metric definitions of the service chosen in the filter bar. */
  protected readonly filterMetrics = computed(() => {
    const serviceId = this.feed.filters().serviceId;
    return serviceId === null ? [] : (this.catalog.metrics()[serviceId]?.items ?? []);
  });

  /** metric id to its name and its service's name, for the rows. */
  private readonly names = computed(() => {
    const services = new Map(this.catalog.services().items.map((s) => [s.id, s.name]));
    const index = new Map<string, { metric: string; service: string | undefined }>();
    for (const [serviceId, list] of Object.entries(this.catalog.metrics())) {
      for (const metric of list.items) {
        index.set(metric.id, { metric: metric.name, service: services.get(serviceId) });
      }
    }
    return index;
  });

  protected readonly pageLabel = computed(() => {
    const pages = Math.max(this.feed.totalPages(), 1);
    return `Page ${this.feed.page() + 1} of ${pages}`;
  });

  constructor() {
    const stream = inject(AnomalyStreamService);
    const release = stream.retain();
    inject(DestroyRef).onDestroy(release);

    this.feed.load();
    if (this.catalog.services().status === 'idle') {
      this.catalog.loadServices();
    }
    // Rows show metric names; ask only for the services that appear in the list or the filter.
    effect(() => {
      const wanted = new Set(this.feed.items().map((item) => item.serviceId));
      const chosen = this.feed.filters().serviceId;
      if (chosen !== null) {
        wanted.add(chosen);
      }
      this.catalog.ensureMetrics([...wanted]);
    });
  }

  protected metricName(metricId: string): string | undefined {
    return this.names().get(metricId)?.metric;
  }

  protected serviceName(metricId: string): string | undefined {
    return this.names().get(metricId)?.service;
  }

  protected onSeverity(value: string): void {
    this.feed.setFilters({ severity: value === '' ? null : (value as Severity) });
  }

  protected onStatus(value: string): void {
    this.feed.setFilters({ status: value as AcknowledgedFilter });
  }

  protected onService(value: string): void {
    this.feed.setFilters({ serviceId: value === '' ? null : value });
  }

  protected onMetric(value: string): void {
    this.feed.setFilters({ metricId: value === '' ? null : value });
  }

  protected clearFilters(): void {
    this.feed.setFilters(NO_FILTERS);
  }
}
