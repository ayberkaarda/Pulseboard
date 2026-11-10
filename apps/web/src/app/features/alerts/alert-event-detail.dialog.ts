import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';

import { absoluteTime } from '../../shared/format/format-time';
import { DeliveryGlyphComponent } from '../../shared/signal/delivery-glyph.component';
import { AlertEvent, DELIVERY_LABELS } from './alert-models';

/** The payload as pretty printed JSON text. It is only ever rendered as text. */
export function payloadText(payload: unknown): string {
  try {
    return JSON.stringify(payload, null, 2) ?? '';
  } catch {
    return '';
  }
}

/**
 * One alert event in full: delivery state, timing, the last error and the payload that was (or will
 * be) delivered. Everything from the server is interpolated as text; the payload sits in a `<pre>`
 * that scrolls sideways, so long lines never widen the dialog.
 */
@Component({
  selector: 'app-alert-event-detail-dialog',
  imports: [MatDialogModule, MatButtonModule, DeliveryGlyphComponent],
  template: `
    <h2 mat-dialog-title class="title">Alert event</h2>
    <mat-dialog-content>
      <p class="status">
        <app-delivery-glyph [state]="event.deliveryStatus" />
        <strong>{{ statusLabel() }}</strong>
        <span>for rule {{ event.ruleName }}</span>
      </p>
      <dl class="facts">
        <div>
          <dt>Triggered</dt>
          <dd class="pb-figures">{{ triggered() }}</dd>
        </div>
        @if (delivered(); as at) {
          <div>
            <dt>Delivered</dt>
            <dd class="pb-figures">{{ at }}</dd>
          </div>
        }
        @if (next(); as at) {
          <div>
            <dt>Next attempt</dt>
            <dd class="pb-figures">{{ at }}</dd>
          </div>
        }
        <div>
          <dt>Attempts</dt>
          <dd class="pb-figures">{{ event.attempts }}</dd>
        </div>
        @if (event.lastError) {
          <div>
            <dt>Last error</dt>
            <dd class="error-text">{{ event.lastError }}</dd>
          </div>
        }
        <div>
          <dt>Event id</dt>
          <dd class="mono">{{ event.id }}</dd>
        </div>
      </dl>

      <div class="payload-head">
        <h3 id="payload-heading">Payload</h3>
        <button mat-stroked-button type="button" (click)="copy()">Copy payload</button>
      </div>
      <pre class="payload" tabindex="0" role="region" aria-labelledby="payload-heading">{{
        json()
      }}</pre>
      <p class="copy-status" role="status">{{ copyStatus() }}</p>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-flat-button type="button" mat-dialog-close>Close</button>
    </mat-dialog-actions>
  `,
  styles: `
    .title {
      font-size: var(--pb-text-2xl);
      font-weight: 600;
      font-stretch: var(--pb-stretch-condensed);
      letter-spacing: -0.015em;
    }
    .status {
      display: flex;
      align-items: center;
      flex-wrap: wrap;
      gap: var(--pb-space-2);
      margin-bottom: var(--pb-space-3);
    }
    .facts {
      display: grid;
      gap: var(--pb-space-2);
      margin: 0 0 var(--pb-space-4);
    }
    .facts > div {
      display: grid;
      grid-template-columns: 8rem minmax(0, 1fr);
      gap: var(--pb-space-3);
    }
    dt {
      color: var(--pb-ink-2);
      font-size: var(--pb-text-sm);
    }
    dd {
      margin: 0;
      overflow-wrap: anywhere;
    }
    .mono {
      font-family: ui-monospace, 'Cascadia Mono', 'Consolas', monospace;
      font-size: var(--pb-text-sm);
    }
    .payload-head {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: var(--pb-space-3);
      margin-bottom: var(--pb-space-2);
    }
    h3 {
      font-size: var(--pb-text-lg);
      font-weight: 600;
    }
    .payload {
      max-height: 280px;
      margin: 0;
      padding: var(--pb-space-3);
      overflow: auto;
      background: var(--pb-surface-sunken);
      border: 1px solid var(--pb-line);
      border-radius: var(--pb-radius-sm);
      font-family: ui-monospace, 'Cascadia Mono', 'Consolas', monospace;
      font-size: var(--pb-text-sm);
      line-height: 1.5;
      white-space: pre;
    }
    .copy-status {
      min-height: 1.5em;
      margin-top: var(--pb-space-1);
      color: var(--pb-ink-2);
      font-size: var(--pb-text-sm);
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AlertEventDetailDialog {
  protected readonly event = inject<AlertEvent>(MAT_DIALOG_DATA);

  protected readonly copyStatus = signal('');
  protected readonly json = computed(() => payloadText(this.event.payload));
  protected readonly statusLabel = computed(() => DELIVERY_LABELS[this.event.deliveryStatus]);
  protected readonly triggered = computed(() => stamp(this.event.triggeredAt));
  protected readonly delivered = computed(() =>
    this.event.deliveredAt ? stamp(this.event.deliveredAt) : null,
  );
  protected readonly next = computed(() =>
    this.event.nextAttemptAt ? stamp(this.event.nextAttemptAt) : null,
  );

  protected async copy(): Promise<void> {
    try {
      await navigator.clipboard.writeText(this.json());
      this.copyStatus.set('Payload copied.');
    } catch {
      this.copyStatus.set('Copying is not available here. Select the text and copy it.');
    }
  }
}

function stamp(iso: string): string {
  const ms = Date.parse(iso);
  return Number.isNaN(ms) ? iso : absoluteTime(ms);
}
