import { Injectable, inject } from '@angular/core';

import { SessionLifecycle } from '../../core/session/session-lifecycle';

/** The part of Material's dialog service this needs, so the service does not pull the library in. */
interface ClosesDialogs {
  closeAll(): void;
}

/**
 * Keeps overlays from outliving the session. When the session is cleared (logout, expired session,
 * another identity) every open dialog is closed, and a dialog that was still being loaded at that
 * moment must not open afterwards: callers take a token before the lazy import and check it after.
 */
@Injectable({ providedIn: 'root' })
export class OverlaySession {
  private epoch = 0;
  private dialogs: ClosesDialogs | null = null;

  constructor() {
    inject(SessionLifecycle).onClear(() => {
      this.epoch += 1;
      this.dialogs?.closeAll();
    });
  }

  /** Taken before something is loaded lazily. */
  token(): number {
    return this.epoch;
  }

  /** True while the session the token was taken in is still the current one. */
  isCurrent(token: number): boolean {
    return token === this.epoch;
  }

  /** Remembers the dialog service so a session clear can close what it opened. */
  track(dialogs: ClosesDialogs): void {
    this.dialogs = dialogs;
  }
}
