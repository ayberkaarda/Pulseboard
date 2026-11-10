import { Injectable } from '@angular/core';

/**
 * Lets stores drop their data when the session ends, without the session store knowing about them.
 * A store registers one callback; logout runs all of them.
 */
@Injectable({ providedIn: 'root' })
export class SessionLifecycle {
  private readonly callbacks = new Set<() => void>();

  onClear(callback: () => void): void {
    this.callbacks.add(callback);
  }

  clear(): void {
    for (const callback of this.callbacks) {
      callback();
    }
  }
}
