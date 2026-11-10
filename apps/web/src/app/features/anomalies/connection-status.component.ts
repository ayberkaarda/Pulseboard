import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  effect,
  inject,
  signal,
} from '@angular/core';
import { format } from 'date-fns';

import { AnomalyStreamService, StreamStatus } from './anomaly-stream.service';

/** Wait this long after a change before it is announced, so a flapping connection says one thing. */
export const ANNOUNCE_DELAY_MS = 2500;

const TEXT: Readonly<Record<StreamStatus, string>> = {
  idle: 'Live updates are off',
  connecting: 'Connecting',
  live: 'Live',
  reconnecting: 'Reconnecting',
  offline: 'Offline',
  paused: 'Paused while this tab was hidden',
};

/**
 * Whether live updates are flowing, in words (never colour alone): Live with the time of the last
 * event, Reconnecting, or Offline. The visible text changes at once; the polite live region repeats a
 * state only after it has held for a moment, so a connection that flaps does not flood a screen reader.
 */
@Component({
  selector: 'app-connection-status',
  template: `
    <p class="status" [attr.data-status]="status()">
      <span class="mark" aria-hidden="true"></span>
      <span>{{ text() }}</span>
    </p>
    <span class="pb-visually-hidden" role="status" aria-live="polite">{{ announcement() }}</span>
  `,
  styles: `
    .status {
      display: inline-flex;
      align-items: center;
      gap: var(--pb-space-2);
      color: var(--pb-ink-2);
      font-size: var(--pb-text-sm);
    }
    .mark {
      width: 8px;
      height: 8px;
      border: 1.5px solid var(--pb-ink-3);
      border-radius: 50%;
    }
    [data-status='live'] .mark {
      background: var(--pb-ink);
      border-color: var(--pb-ink);
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ConnectionStatusComponent {
  private readonly stream = inject(AnomalyStreamService);
  protected readonly status = this.stream.status;
  protected readonly announcement = signal('');

  protected readonly text = computed(() => {
    const status = this.stream.status();
    const last = this.stream.lastEventAt();
    return status === 'live' && last !== null
      ? `${TEXT.live}, last event ${format(last, 'HH:mm:ss')}`
      : TEXT[status];
  });

  constructor() {
    let timer: ReturnType<typeof setTimeout> | null = null;
    effect(() => {
      const status = this.stream.status();
      if (timer !== null) {
        clearTimeout(timer);
      }
      timer = setTimeout(() => {
        this.announcement.set(
          status === 'idle' ? '' : `Live updates: ${TEXT[status].toLowerCase()}`,
        );
      }, ANNOUNCE_DELAY_MS);
    });
    inject(DestroyRef).onDestroy(() => {
      if (timer !== null) {
        clearTimeout(timer);
      }
    });
  }
}
