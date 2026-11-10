import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { RouterLink } from '@angular/router';

import { PageHeadComponent } from '../../shared/page-head/page-head.component';
import { EmptyStateComponent } from '../../shell/empty-state/empty-state.component';

/**
 * Extension point for the metric chart page.
 * TODO(4b): load GET /api/v1/metrics/{metricId}/points for a time range and draw the series with its
 * expected range band and anomaly markers (see DESIGN.md, chart grammar).
 */
@Component({
  selector: 'app-metric-detail-page',
  imports: [RouterLink, EmptyStateComponent, PageHeadComponent],
  template: `
    <app-page-head
      title="Metric"
      summary="Values over time, drawn against the range the detector expects."
    />
    <app-empty-state
      heading="The metric chart is not available yet"
      body="This page will draw the values of this metric against its expected range."
    >
      <a routerLink="/app/overview">Back to overview</a>
    </app-empty-state>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MetricDetailPage {
  /** Route parameter, bound through withComponentInputBinding. */
  readonly id = input.required<string>();
}
