import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  effect,
  inject,
  input,
  signal,
} from '@angular/core';

export const BATCH_MS = 5000;

/**
 * Tells a screen reader that new anomalies arrived, once per batch: the count grows silently and the
 * polite live region is updated at most every five seconds with the total since the last message.
 */
@Component({
  selector: 'app-live-announcer',
  template: `<span class="pb-visually-hidden" role="status" aria-live="polite">{{
    message()
  }}</span>`,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LiveAnnouncerComponent {
  /** Total live events so far; a drop (the list was reloaded) restarts the count. */
  readonly count = input.required<number>();
  protected readonly message = signal('');

  constructor() {
    let announced = 0;
    let flip = false;
    let timer: ReturnType<typeof setTimeout> | null = null;
    effect(() => {
      const count = this.count();
      if (count < announced) {
        announced = count;
      }
      if (count > announced && timer === null) {
        timer = setTimeout(() => {
          timer = null;
          const fresh = this.count() - announced;
          announced = this.count();
          if (fresh > 0) {
            flip = !flip;
            // A zero width space makes two identical messages in a row different, so both are read.
            const text = `${fresh} new ${fresh === 1 ? 'anomaly' : 'anomalies'}`;
            this.message.set(flip ? text : `${text}\u200b`);
          }
        }, BATCH_MS);
      }
    });
    inject(DestroyRef).onDestroy(() => {
      if (timer !== null) {
        clearTimeout(timer);
      }
    });
  }
}
