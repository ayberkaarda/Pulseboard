import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';

import { PageHeadComponent } from '../../shared/page-head/page-head.component';

/**
 * The frame of the two alert views. They are sub-routes rather than ARIA tabs: each view has its own
 * address, back/forward work, and the links are ordinary links marked with aria-current.
 */
@Component({
  selector: 'app-alerts-page',
  imports: [PageHeadComponent, RouterLink, RouterLinkActive, RouterOutlet],
  template: `
    <app-page-head
      title="Alerts"
      summary="Get told when a metric crosses a line you chose, or when an anomaly is serious enough."
    />
    <nav class="views" aria-label="Alert views">
      <a routerLink="rules" routerLinkActive="current" ariaCurrentWhenActive="page">Rules</a>
      <a routerLink="history" routerLinkActive="current" ariaCurrentWhenActive="page">History</a>
    </nav>
    <router-outlet />
  `,
  styles: `
    .views {
      display: flex;
      gap: var(--pb-space-1);
      margin-bottom: var(--pb-space-5);
      border-bottom: 1px solid var(--pb-line);
    }
    a {
      padding: var(--pb-space-2) var(--pb-space-4);
      margin-bottom: -1px;
      border-bottom: 3px solid transparent;
      color: var(--pb-ink-2);
      font-weight: 520;
      text-decoration: none;
    }
    a:hover {
      background: var(--pb-wash-hover);
    }
    a.current {
      border-bottom-color: var(--pb-flare);
      color: var(--pb-ink);
      font-weight: 640;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AlertsPage {}
