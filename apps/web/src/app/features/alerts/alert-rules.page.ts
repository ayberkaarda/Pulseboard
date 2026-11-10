import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  computed,
  inject,
  viewChild,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { RouterLink } from '@angular/router';

import { ConfirmService } from '../../shared/confirm/confirm.service';
import { LoadErrorComponent } from '../../shared/load-state/load-error.component';
import { SkeletonRowsComponent } from '../../shared/load-state/skeleton-rows.component';
import { EmptyStateComponent } from '../../shell/empty-state/empty-state.component';
import {
  AlertRule,
  CHANNEL_LABELS,
  conditionSummary,
  formatCooldown,
  targetDisplay,
} from './alert-models';
import { AlertRulesStore } from './alert-rules.store';

/**
 * The rules of the workspace. Owners and admins get the switch, the edit and delete actions and the
 * "New rule" button. Everyone else gets a read-only list: those controls are not rendered at all,
 * and a note says why.
 */
@Component({
  selector: 'app-alert-rules-page',
  imports: [
    MatButtonModule,
    MatSlideToggleModule,
    RouterLink,
    EmptyStateComponent,
    LoadErrorComponent,
    SkeletonRowsComponent,
  ],
  templateUrl: './alert-rules.page.html',
  styleUrl: './alert-rules.page.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AlertRulesPage {
  protected readonly store = inject(AlertRulesStore);
  private readonly confirmation = inject(ConfirmService);
  private readonly heading = viewChild.required<ElementRef<HTMLElement>>('heading');

  /** What the body shows. A reload keeps the rules on screen. */
  protected readonly view = computed(() => {
    const status = this.store.status();
    if (this.store.rules().length > 0) {
      return 'list';
    }
    if (status === 'error') {
      return 'error';
    }
    return status === 'loaded' ? 'empty' : 'loading';
  });

  constructor() {
    this.store.load();
  }

  protected summary(rule: AlertRule): string {
    return conditionSummary(rule);
  }

  protected cooldown(rule: AlertRule): string {
    return formatCooldown(rule.cooldownSeconds);
  }

  protected channel(rule: AlertRule): string {
    return CHANNEL_LABELS[rule.channel];
  }

  protected where(rule: AlertRule): string {
    return targetDisplay(rule.channel, rule.target);
  }

  protected toggle(rule: AlertRule, enabled: boolean): void {
    void this.store.toggle(rule, enabled);
  }

  protected async remove(rule: AlertRule): Promise<void> {
    const confirmed = await this.confirmation.confirm({
      title: 'Delete this rule?',
      body: `"${rule.name}" and its history of alerts are deleted. This cannot be undone.`,
      confirmLabel: 'Delete rule',
    });
    if (confirmed && (await this.store.remove(rule))) {
      // The row that had focus is gone; keep the person at the list instead of at the page top.
      this.heading().nativeElement.focus();
    }
  }
}
