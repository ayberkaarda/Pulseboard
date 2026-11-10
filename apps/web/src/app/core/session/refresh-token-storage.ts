import { DOCUMENT } from '@angular/common';
import { Injectable, inject } from '@angular/core';

export const REFRESH_TOKEN_STORAGE_KEY = 'pulseboard.session';

export interface StoredRefreshToken {
  readonly refreshToken: string;
  /** Epoch milliseconds. */
  readonly expiresAt: number;
}

/**
 * Keeps the refresh token in sessionStorage so a reload does not sign the person out, while the
 * token still dies with the tab. Every access is guarded: with storage blocked, full or absent the
 * session simply lives in memory (a reload then signs out). The access token is never written here.
 */
@Injectable({ providedIn: 'root' })
export class RefreshTokenStorage {
  private readonly document = inject(DOCUMENT);

  read(): StoredRefreshToken | null {
    try {
      const raw = this.document.defaultView?.sessionStorage.getItem(REFRESH_TOKEN_STORAGE_KEY);
      if (!raw) {
        return null;
      }
      const value: unknown = JSON.parse(raw);
      if (typeof value !== 'object' || value === null) {
        return null;
      }
      const { refreshToken, expiresAt } = value as Record<string, unknown>;
      return typeof refreshToken === 'string' &&
        refreshToken.length > 0 &&
        typeof expiresAt === 'number'
        ? { refreshToken, expiresAt }
        : null;
    } catch {
      return null;
    }
  }

  write(value: StoredRefreshToken): void {
    try {
      this.document.defaultView?.sessionStorage.setItem(
        REFRESH_TOKEN_STORAGE_KEY,
        JSON.stringify(value),
      );
    } catch {
      // Storage is unavailable or full: the session continues in memory only.
    }
  }

  clear(): void {
    try {
      this.document.defaultView?.sessionStorage.removeItem(REFRESH_TOKEN_STORAGE_KEY);
    } catch {
      // Nothing to clear when storage cannot be reached.
    }
  }
}
