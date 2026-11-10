import type { components } from './generated/schema';

type Schemas = components['schemas'];

export type Environment = NonNullable<Schemas['ServiceResponse']['environment']>;
export type Aggregation = NonNullable<Schemas['MetricDefinitionResponse']['aggregation']>;

/** The contract leaves every response field optional; these are the values the UI requires. */
export interface Service {
  readonly id: string;
  readonly name: string;
  readonly environment: Environment;
  readonly createdAt: string;
}

export interface MetricDefinition {
  readonly id: string;
  readonly serviceId: string;
  readonly name: string;
  readonly unit: string;
  readonly aggregation: Aggregation;
  readonly createdAt: string;
}

export interface TokenPair {
  readonly accessToken: string;
  /** Epoch milliseconds. */
  readonly accessTokenExpiresAt: number;
  readonly refreshToken: string;
  /** Epoch milliseconds. */
  readonly refreshTokenExpiresAt: number;
  readonly userId: string;
  readonly tenantId: string;
  readonly role: string;
}

const ENVIRONMENTS: readonly string[] = ['PROD', 'STAGING', 'DEV'];
const AGGREGATIONS: readonly string[] = ['AVG', 'SUM', 'MAX', 'P95'];

function filled(value: unknown): value is string {
  return typeof value === 'string' && value.length > 0;
}

export function toService(raw: Schemas['ServiceResponse']): Service | null {
  const { id, name, environment, createdAt } = raw;
  return filled(id) && filled(name) && filled(environment) && ENVIRONMENTS.includes(environment)
    ? { id, name, environment, createdAt: createdAt ?? '' }
    : null;
}

export function toMetricDefinition(
  raw: Schemas['MetricDefinitionResponse'],
): MetricDefinition | null {
  const { id, serviceId, name, unit, aggregation, createdAt } = raw;
  return filled(id) &&
    filled(serviceId) &&
    filled(name) &&
    filled(aggregation) &&
    AGGREGATIONS.includes(aggregation)
    ? { id, serviceId, name, unit: unit ?? '', aggregation, createdAt: createdAt ?? '' }
    : null;
}

/** Null when the token response lacks a field the session depends on. */
export function toTokenPair(raw: Schemas['TokenResponse']): TokenPair | null {
  const access = Date.parse(raw.accessTokenExpiresAt ?? '');
  const refresh = Date.parse(raw.refreshTokenExpiresAt ?? '');
  if (
    !filled(raw.accessToken) ||
    !filled(raw.refreshToken) ||
    !filled(raw.userId) ||
    !filled(raw.tenantId) ||
    Number.isNaN(access) ||
    Number.isNaN(refresh)
  ) {
    return null;
  }
  return {
    accessToken: raw.accessToken,
    accessTokenExpiresAt: access,
    refreshToken: raw.refreshToken,
    refreshTokenExpiresAt: refresh,
    userId: raw.userId,
    tenantId: raw.tenantId,
    role: raw.role ?? 'VIEWER',
  };
}
