import { DestroyRef, Injectable, inject, signal } from '@angular/core';

const TICK_MS = 30_000;

/**
 * "Now" as a signal that moves every 30 seconds, so relative times and the one hour window of the
 * overview keep ageing while a page stays open. The timer starts on first read.
 */
@Injectable({ providedIn: 'root' })
export class Clock {
  private readonly current = signal(Date.now());
  private timer: ReturnType<typeof setInterval> | null = null;

  constructor() {
    inject(DestroyRef).onDestroy(() => {
      if (this.timer !== null) {
        clearInterval(this.timer);
      }
    });
  }

  /** Epoch milliseconds, refreshed every 30 seconds. */
  now(): number {
    this.timer ??= setInterval(() => this.current.set(Date.now()), TICK_MS);
    return this.current();
  }

  /** The exact current time, for decisions (a request window) rather than display. */
  exactNow(): number {
    return Date.now();
  }
}
