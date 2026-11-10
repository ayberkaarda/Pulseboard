import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { RouterLink } from '@angular/router';

import { Anomaly } from '../../api/models';
import { Clock } from '../../core/time/clock.service';
import { absoluteTime, relativeTime } from '../../shared/format/format-time';
import { SeverityGlyphComponent } from '../../shared/signal/severity-glyph.component';
import { DETECTOR_LABELS, SEVERITY_LABELS } from '../../shared/signal/signal.model';
import { AnomalyActions } from './anomaly-actions.service';

/**
 * One anomaly as a row (a card on narrow screens): severity as glyph and word, the metric it belongs
 * to, when its window started (relative, with the exact time in the title), its state and the
 * acknowledge action. Where the person may not acknowledge, the button stays focusable but inert and
 * points at the note that explains why (`noteId`).
 */
@Component({
  selector: 'app-anomaly-row',
  imports: [MatButtonModule, RouterLink, SeverityGlyphComponent],
  template: `
    <div class="sev">
      <app-severity-glyph [severity]="anomaly().severity" />
      <span>{{ severityLabel() }}</span>
    </div>
    <div class="what">
      @if (linkToMetric()) {
        <a
          class="metric"
          [routerLink]="['/app/metrics', anomaly().metricId]"
          [queryParams]="{ service: anomaly().serviceId }"
          >{{ metricName() ?? 'Metric ' + shortId() }}</a
        >
        @if (serviceName(); as service) {
          <span class="service">{{ service }}</span>
        }
      }
      <span class="meta">{{ detector() }}, score {{ score() }}</span>
    </div>
    <time class="when" [attr.datetime]="anomaly().windowStart" [title]="absolute()">{{
      relative()
    }}</time>
    <div class="state">
      @if (acknowledged()) {
        <span class="done">Acknowledged</span>
      } @else {
        <span class="open">Open</span>
        <button
          mat-stroked-button
          type="button"
          disabledInteractive
          [disabled]="!actions.canAcknowledge() || pending()"
          [attr.aria-label]="ackLabel()"
          [attr.aria-describedby]="actions.canAcknowledge() ? null : noteId()"
          [attr.aria-busy]="pending()"
          (click)="actions.acknowledge(anomaly())"
        >
          Acknowledge
        </button>
      }
    </div>
  `,
  styles: `
    :host {
      display: grid;
      grid-template-columns: 96px minmax(0, 1fr) 140px auto;
      align-items: center;
      gap: var(--pb-space-4);
      padding: var(--pb-space-3) var(--pb-space-4);
      background: var(--pb-surface);
      border: 1px solid var(--pb-line);
      border-radius: var(--pb-radius-md);
    }
    .sev,
    .state {
      display: flex;
      align-items: center;
      gap: var(--pb-space-2);
    }
    .sev {
      font-weight: 600;
    }
    .what {
      display: grid;
      min-width: 0;
    }
    .metric {
      overflow: hidden;
      color: var(--pb-ink);
      font-weight: 600;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    .service,
    .meta,
    .when {
      color: var(--pb-ink-2);
      font-size: var(--pb-text-sm);
    }
    .state {
      justify-content: flex-end;
      min-width: 200px;
    }
    .open,
    .done {
      font-size: var(--pb-text-sm);
    }
    .open {
      font-weight: 600;
    }
    .done {
      color: var(--pb-ink-2);
    }
    @media (max-width: 719.98px) {
      :host {
        grid-template-columns: minmax(0, 1fr) auto;
        gap: var(--pb-space-2) var(--pb-space-3);
      }
      .what,
      .state {
        grid-column: 1 / -1;
      }
      .state {
        justify-content: space-between;
        min-width: 0;
      }
      .when {
        text-align: right;
      }
    }
  `,
  host: { role: 'listitem' },
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AnomalyRowComponent {
  readonly anomaly = input.required<Anomaly>();
  readonly metricName = input<string>();
  readonly serviceName = input<string>();
  readonly linkToMetric = input(true);
  /** Id of the element that explains why acknowledging is unavailable. */
  readonly noteId = input('ack-note');

  protected readonly actions = inject(AnomalyActions);
  private readonly clock = inject(Clock);

  protected readonly severityLabel = computed(() => SEVERITY_LABELS[this.anomaly().severity]);
  protected readonly detector = computed(
    () => DETECTOR_LABELS[this.anomaly().detector] ?? this.anomaly().detector,
  );
  protected readonly score = computed(() => this.anomaly().score.toFixed(2));
  protected readonly shortId = computed(() => this.anomaly().metricId.slice(0, 8));
  protected readonly acknowledged = computed(() => this.anomaly().acknowledgedAt !== null);
  protected readonly pending = computed(() => this.actions.pending().has(this.anomaly().id));
  protected readonly absolute = computed(() => absoluteTime(this.anomaly().startMs));
  protected readonly relative = computed(() =>
    relativeTime(this.anomaly().startMs, this.clock.now()),
  );
  protected readonly ackLabel = computed(
    () =>
      `Acknowledge ${this.severityLabel().toLowerCase()} anomaly${
        this.metricName() ? ` on ${this.metricName()}` : ''
      }, ${this.relative()}`,
  );
}
