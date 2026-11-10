import { ChangeDetectionStrategy, Component, inject, OnInit } from '@angular/core';
import { RouterLink } from '@angular/router';

import { HealthGlyphComponent } from '../../shared/signal/health-glyph.component';
import { HEALTH_LABELS } from '../../shared/signal/signal.model';
import { LoadErrorComponent } from '../../shared/load-state/load-error.component';
import { SkeletonRowsComponent } from '../../shared/load-state/skeleton-rows.component';
import { PageHeadComponent } from '../../shared/page-head/page-head.component';
import { EmptyStateComponent } from '../../shell/empty-state/empty-state.component';
import { ServicesStore } from '../services/services.store';

/**
 * Lists the tenant's services. Health is not computed yet, so every service shows "No data".
 * TODO(4b): replace the placeholder health with the latest anomaly state and a sparkline per service.
 */
@Component({
  selector: 'app-overview-page',
  imports: [
    RouterLink,
    EmptyStateComponent,
    HealthGlyphComponent,
    LoadErrorComponent,
    PageHeadComponent,
    SkeletonRowsComponent,
  ],
  templateUrl: './overview.page.html',
  styleUrl: './overview.page.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OverviewPage implements OnInit {
  protected readonly store = inject(ServicesStore);
  protected readonly unknownLabel = HEALTH_LABELS.unknown;

  ngOnInit(): void {
    this.store.loadServices();
  }
}
