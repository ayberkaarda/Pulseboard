import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  effect,
  inject,
} from '@angular/core';
import { RouterLink } from '@angular/router';

import { Service } from '../../api/models';
import { Clock } from '../../core/time/clock.service';
import { relativeTime, absoluteTime } from '../../shared/format/format-time';
import { LoadErrorComponent } from '../../shared/load-state/load-error.component';
import { SkeletonRowsComponent } from '../../shared/load-state/skeleton-rows.component';
import { PageHeadComponent } from '../../shared/page-head/page-head.component';
import { HealthGlyphComponent } from '../../shared/signal/health-glyph.component';
import { SeverityGlyphComponent } from '../../shared/signal/severity-glyph.component';
import { HEALTH_LABELS, HealthState, SEVERITY_LABELS } from '../../shared/signal/signal.model';
import { EmptyStateComponent } from '../../shell/empty-state/empty-state.component';
import { AnomalyStreamService } from '../anomalies/anomaly-stream.service';
import { ConnectionStatusComponent } from '../anomalies/connection-status.component';
import { ServicesStore } from '../services/services.store';
import { ServiceHealth, compareByUrgency, deriveHealth } from './service-health';
import { ServiceHealthStore } from './service-health.store';

interface Tile {
  readonly service: Service;
  readonly health: ServiceHealth;
  readonly metricCount: number | null;
}

const STATES: readonly HealthState[] = ['healthy', 'degraded', 'down', 'unknown'];

/**
 * The service health board. Health comes from the unacknowledged anomalies of the last hour (one
 * request for all services, indexed by the service id each anomaly carries) and from whether the
 * service defines metrics at all (one small request per service, at most four at a time). The live
 * stream moves tiles without any further request.
 */
@Component({
  selector: 'app-overview-page',
  imports: [
    RouterLink,
    ConnectionStatusComponent,
    EmptyStateComponent,
    HealthGlyphComponent,
    LoadErrorComponent,
    PageHeadComponent,
    SeverityGlyphComponent,
    SkeletonRowsComponent,
  ],
  templateUrl: './overview.page.html',
  styleUrl: './overview.page.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OverviewPage {
  protected readonly store = inject(ServicesStore);
  protected readonly healthStore = inject(ServiceHealthStore);
  private readonly clock = inject(Clock);

  protected readonly labels = HEALTH_LABELS;
  protected readonly severityLabels = SEVERITY_LABELS;
  protected readonly states = STATES;

  /** Tiles are only trusted to say "healthy" once the anomalies of the hour are known. */
  protected readonly tiles = computed<Tile[]>(() => {
    const anomaliesKnown = this.healthStore.status() === 'loaded';
    const nowMs = this.clock.now();
    return this.store
      .services()
      .items.map((service) => {
        const metrics = this.store.metrics()[service.id];
        const metricCount =
          anomaliesKnown && metrics?.status === 'loaded' ? metrics.items.length : null;
        const health = deriveHealth(
          this.healthStore.byService().get(service.id) ?? [],
          metricCount,
          nowMs,
        );
        return { service, health, metricCount };
      })
      .sort((a, b) =>
        compareByUrgency(
          { name: a.service.name, health: a.health },
          { name: b.service.name, health: b.health },
        ),
      );
  });

  protected readonly counts = computed(() => {
    const counts: Record<HealthState, number> = { healthy: 0, degraded: 0, down: 0, unknown: 0 };
    for (const tile of this.tiles()) {
      counts[tile.health.state] += 1;
    }
    return counts;
  });

  constructor() {
    const release = inject(AnomalyStreamService).retain();
    inject(DestroyRef).onDestroy(release);

    this.store.loadServices();
    this.healthStore.load();
    effect(() => {
      this.store.ensureMetrics(this.store.services().items.map((service) => service.id));
    });
  }

  protected reload(): void {
    this.store.loadServices();
    this.healthStore.load();
  }

  protected lastSeen(ms: number): string {
    return relativeTime(ms, this.clock.now());
  }

  protected exact(ms: number): string {
    return absoluteTime(ms);
  }

  protected describe(tile: Tile): string {
    const { state, openCount } = tile.health;
    if (state === 'unknown') {
      return tile.metricCount === 0 ? 'No metrics defined yet' : 'No data to judge health from';
    }
    if (openCount === 0) {
      return 'No open anomalies in the last hour';
    }
    return `${openCount} open ${openCount === 1 ? 'anomaly' : 'anomalies'} in the last hour`;
  }
}
