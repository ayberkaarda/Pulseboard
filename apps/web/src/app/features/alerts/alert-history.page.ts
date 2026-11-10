import {
  ChangeDetectionStrategy,
  Component,
  Injector,
  computed,
  effect,
  inject,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { ActivatedRoute } from '@angular/router';

import { Clock } from '../../core/time/clock.service';
import { absoluteTime, relativeTime } from '../../shared/format/format-time';
import { LoadErrorComponent } from '../../shared/load-state/load-error.component';
import { SkeletonRowsComponent } from '../../shared/load-state/skeleton-rows.component';
import { DeliveryGlyphComponent } from '../../shared/signal/delivery-glyph.component';
import { EmptyStateComponent } from '../../shell/empty-state/empty-state.component';
import { AlertEvent, DELIVERY_LABELS, DeliveryStatus } from './alert-models';
import { AlertHistoryStore, shouldAutoRefresh } from './alert-history.store';
import { AlertRulesStore } from './alert-rules.store';

/** While something is pending and the tab is visible the history is read again this often. */
export const AUTO_REFRESH_MS = 30_000;

const RULE_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/**
 * Alert history: events newest first, filtered and paged on the server. A row shows the delivery
 * state (glyph and word), attempts and the sanitised last error as plain text; "Details" opens the
 * payload. While anything is pending the list refreshes gently every 30 seconds, only when the tab
 * is visible, and not at all otherwise.
 */
@Component({
  selector: 'app-alert-history-page',
  imports: [
    MatButtonModule,
    DeliveryGlyphComponent,
    EmptyStateComponent,
    LoadErrorComponent,
    SkeletonRowsComponent,
  ],
  templateUrl: './alert-history.page.html',
  styleUrl: './alert-history.page.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AlertHistoryPage {
  protected readonly history = inject(AlertHistoryStore);
  protected readonly rules = inject(AlertRulesStore);
  private readonly clock = inject(Clock);
  private readonly injector = inject(Injector);

  protected readonly pageLabel = computed(() => {
    const pages = Math.max(this.history.totalPages(), 1);
    return `Page ${this.history.page() + 1} of ${pages}`;
  });

  protected readonly loadedAt = computed(() => {
    const at = this.history.loadedAt();
    return at === null ? null : absoluteTime(at);
  });

  constructor() {
    const ruleId = inject(ActivatedRoute).snapshot.queryParamMap.get('ruleId');
    if (ruleId !== null && RULE_ID.test(ruleId)) {
      this.history.setFilters({ ruleId });
    } else {
      this.history.load();
    }
    this.rules.ensureLoaded();

    // The timer exists only while there is something to wait for.
    effect((onCleanup) => {
      if (!this.history.hasPending()) {
        return;
      }
      const timer = setInterval(() => {
        const visible = typeof document === 'undefined' || document.visibilityState === 'visible';
        if (shouldAutoRefresh(this.history.hasPending(), visible)) {
          this.history.refresh();
        }
      }, AUTO_REFRESH_MS);
      onCleanup(() => clearInterval(timer));
    });
  }

  protected statusLabel(status: DeliveryStatus): string {
    return DELIVERY_LABELS[status];
  }

  protected relative(event: AlertEvent): string {
    return relativeTime(Date.parse(event.triggeredAt), this.clock.now());
  }

  protected absolute(event: AlertEvent): string {
    return absoluteTime(Date.parse(event.triggeredAt));
  }

  protected onRule(value: string): void {
    this.history.setFilters({ ruleId: value === '' ? null : value });
  }

  protected onStatus(value: string): void {
    this.history.setFilters({ status: value === '' ? null : (value as DeliveryStatus) });
  }

  protected onFrom(value: string): void {
    this.history.setFilters({ from: value === '' ? null : value });
  }

  protected onTo(value: string): void {
    this.history.setFilters({ to: value === '' ? null : value });
  }

  /** The dialog and Material's overlay load when a row is opened. */
  protected async open(event: AlertEvent): Promise<void> {
    const [{ MatDialog }, { AlertEventDetailDialog }] = await Promise.all([
      import('@angular/material/dialog'),
      import('./alert-event-detail.dialog'),
    ]);
    this.injector.get(MatDialog).open(AlertEventDetailDialog, {
      data: event,
      width: '680px',
      maxWidth: 'calc(100vw - 32px)',
      autoFocus: 'dialog',
      restoreFocus: true,
    });
  }
}
