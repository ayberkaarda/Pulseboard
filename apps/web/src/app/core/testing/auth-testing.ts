import { Component } from '@angular/core';

import type { components } from '../../api/generated/schema';

type TokenResponse = components['schemas']['TokenResponse'];

/** Test double for routed pages. */
@Component({ selector: 'app-test-stub', template: '' })
export class StubPage {}

export function tokenResponse(
  generation: number,
  overrides: Partial<TokenResponse> = {},
): TokenResponse {
  const hour = 60 * 60 * 1000;
  return {
    accessToken: `access-${generation}`,
    accessTokenExpiresAt: new Date(Date.now() + 15 * 60 * 1000).toISOString(),
    refreshToken: `refresh-${generation}`,
    refreshTokenExpiresAt: new Date(Date.now() + 24 * hour).toISOString(),
    tokenType: 'Bearer',
    userId: '5f0c1d52-0000-4000-8000-000000000001',
    tenantId: '5f0c1d52-0000-4000-8000-000000000002',
    role: 'OWNER',
    ...overrides,
  };
}

/** Lets promise continuations and microtasks run, after a response was flushed. */
export function settle(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}
