import { HttpErrorResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { patchState, signalStore, withComputed, withMethods, withState } from '@ngrx/signals';
import { firstValueFrom } from 'rxjs';

import { ApiClient } from '../../api/api-client';
import type { components } from '../../api/generated/schema';
import { toTokenPair } from '../../api/models';
import { isApiError } from '../../api/problem';
import { quietContext } from '../http/api-request';
import { RefreshTokenStorage } from './refresh-token-storage';
import { SessionLifecycle } from './session-lifecycle';

type LoginRequest = components['schemas']['LoginRequest'];
type RegisterRequest = components['schemas']['RegisterRequest'];
type TokenResponse = components['schemas']['TokenResponse'];

export interface SessionUser {
  readonly userId: string;
  readonly tenantId: string;
  readonly role: string;
}

interface SessionState {
  /** Memory only. Never persisted. */
  accessToken: string | null;
  accessTokenExpiresAt: number | null;
  refreshToken: string | null;
  refreshTokenExpiresAt: number | null;
  user: SessionUser | null;
}

/**
 * - refreshed: new tokens are in place, the caller may retry.
 * - ended: the server rejected the refresh token (or it expired); the session is gone.
 * - unavailable: the refresh could not be completed (network, 5xx, 429); the session is kept.
 */
export type RefreshOutcome = 'refreshed' | 'ended' | 'unavailable';

/** The server looked at the refresh token and refused it (as opposed to not answering). */
function isDefiniteRejection(error: unknown): boolean {
  const status = isApiError(error)
    ? error.problem.status
    : error instanceof HttpErrorResponse
      ? error.status
      : 0;
  return status === 401 || status === 400;
}

const EMPTY: SessionState = {
  accessToken: null,
  accessTokenExpiresAt: null,
  refreshToken: null,
  refreshTokenExpiresAt: null,
  user: null,
};

/** A token that expires this soon is refreshed before it is handed to a long lived connection. */
const FRESHNESS_MARGIN_MS = 30_000;

export const SessionStore = signalStore(
  { providedIn: 'root' },
  withState<SessionState>(() => {
    const stored = inject(RefreshTokenStorage).read();
    return stored && stored.expiresAt > Date.now()
      ? { ...EMPTY, refreshToken: stored.refreshToken, refreshTokenExpiresAt: stored.expiresAt }
      : EMPTY;
  }),
  withComputed((store) => ({
    isAuthenticated: () => store.accessToken() !== null,
    /** A refresh token exists, so a session can be brought back after a reload. */
    canRestore: () => store.refreshToken() !== null,
  })),
  withMethods((store) => {
    const api = inject(ApiClient);
    const storage = inject(RefreshTokenStorage);
    const lifecycle = inject(SessionLifecycle);

    // Bumped by every logout; a response that belongs to an older session is discarded.
    let generation = 0;
    let inFlight: Promise<RefreshOutcome> | null = null;

    function clearSession(): void {
      generation += 1;
      patchState(store, EMPTY);
      storage.clear();
      lifecycle.clear();
    }

    function adopt(raw: TokenResponse): void {
      const tokens = toTokenPair(raw);
      if (tokens === null) {
        throw new Error('The token response is incomplete');
      }
      patchState(store, {
        accessToken: tokens.accessToken,
        accessTokenExpiresAt: tokens.accessTokenExpiresAt,
        refreshToken: tokens.refreshToken,
        refreshTokenExpiresAt: tokens.refreshTokenExpiresAt,
        user: { userId: tokens.userId, tenantId: tokens.tenantId, role: tokens.role },
      });
      storage.write({ refreshToken: tokens.refreshToken, expiresAt: tokens.refreshTokenExpiresAt });
    }

    async function exchange(request: () => Promise<TokenResponse>): Promise<void> {
      const startedIn = generation;
      const raw = await request();
      if (startedIn === generation) {
        adopt(raw);
      }
    }

    async function performRefresh(): Promise<RefreshOutcome> {
      const refreshToken = store.refreshToken();
      const expiresAt = store.refreshTokenExpiresAt();
      if (refreshToken === null || (expiresAt !== null && expiresAt <= Date.now())) {
        clearSession();
        return 'ended';
      }
      const startedIn = generation;
      try {
        const raw = await firstValueFrom(
          api.post('/api/v1/auth/refresh', { body: { refreshToken }, context: quietContext() }),
        );
        if (startedIn !== generation) {
          return 'ended';
        }
        adopt(raw);
        return 'refreshed';
      } catch (error) {
        if (startedIn !== generation) {
          return 'ended';
        }
        // Only a definite rejection ends the session. A flaky network must not sign anyone out.
        if (isDefiniteRejection(error)) {
          clearSession();
          return 'ended';
        }
        return 'unavailable';
      }
    }

    const refresh = (): Promise<RefreshOutcome> => {
      inFlight ??= performRefresh().finally(() => {
        inFlight = null;
      });
      return inFlight;
    };

    return {
      async login(credentials: LoginRequest): Promise<void> {
        await exchange(() =>
          firstValueFrom(
            api.post('/api/v1/auth/login', { body: credentials, context: quietContext() }),
          ),
        );
      },

      async register(details: RegisterRequest): Promise<void> {
        await exchange(() =>
          firstValueFrom(
            api.post('/api/v1/auth/register', { body: details, context: quietContext() }),
          ),
        );
      },

      /**
       * Exchanges the refresh token for a new pair. Concurrent callers share ONE request: the server
       * rotates the token on every exchange and treats a second use of the old one as theft, which
       * would revoke the whole session family.
       */
      refresh,

      /** Brings a session back after a reload. Resolves true when an access token is available. */
      async restore(): Promise<boolean> {
        if (store.accessToken() !== null) {
          return true;
        }
        if (store.refreshToken() === null) {
          return false;
        }
        return (await refresh()) === 'refreshed';
      },

      /**
       * A bearer token for a connection that cannot replay on 401 (the live anomaly stream),
       * refreshed first when it is about to expire. Null when there is no session.
       */
      async freshAccessToken(): Promise<string | null> {
        const expiresAt = store.accessTokenExpiresAt();
        const expiring = expiresAt !== null && expiresAt - Date.now() < FRESHNESS_MARGIN_MS;
        if (store.accessToken() === null || expiring) {
          await refresh();
        }
        return store.accessToken();
      },

      /** Ends the session on this device: tokens, storage and every registered store are cleared. */
      logout(): void {
        clearSession();
      },
    };
  }),
);
