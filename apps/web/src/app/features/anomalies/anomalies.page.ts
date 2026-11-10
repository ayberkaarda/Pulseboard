import { ChangeDetectionStrategy, Component } from '@angular/core';

import { PageHeadComponent } from '../../shared/page-head/page-head.component';
import { EmptyStateComponent } from '../../shell/empty-state/empty-state.component';

/**
 * Extension point for the anomaly feed.
 * TODO(4b): an AnomalyFeedStore reads GET /api/v1/anomalies, then follows /api/v1/stream/anomalies
 * with a fetch based reader (a browser EventSource cannot send the bearer header). Take the bearer from
 * SessionStore.freshAccessToken() on every (re)connect, drop duplicate event ids and re-read the
 * list after each reconnect, because missed events are not replayed.
 */
@Component({
  selector: 'app-anomalies-page',
  imports: [EmptyStateComponent, PageHeadComponent],
  template: `
    <app-page-head
      title="Anomalies"
      summary="Every moment a metric left its expected range, newest first."
    />
    <app-empty-state
      heading="The anomaly feed is not available yet"
      body="When a metric moves outside its expected range, the event will stream in here live."
    />
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AnomaliesPage {}
