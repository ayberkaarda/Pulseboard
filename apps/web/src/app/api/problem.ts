import { HttpErrorResponse } from '@angular/common/http';

export interface ProblemFieldError {
  readonly field: string;
  readonly message: string;
}

/** RFC 9457 problem details as the server sends them, reduced to what the UI relies on. */
export interface ApiProblem {
  /** HTTP status; 0 when no response arrived (offline, DNS, aborted). */
  readonly status: number;
  /** Problem kind: the part after `urn:pulseboard:problem:`, or `unknown`. */
  readonly kind: string;
  readonly title: string;
  readonly detail?: string;
  readonly fieldErrors: readonly ProblemFieldError[];
  /** Seconds from a valid Retry-After header, bounded to one hour. */
  readonly retryAfterSeconds?: number;
}

export class ApiError extends Error {
  constructor(readonly problem: ApiProblem) {
    super(`${problem.status} ${problem.kind}`);
    this.name = 'ApiError';
  }
}

export function isApiError(value: unknown): value is ApiError {
  return value instanceof ApiError;
}

const TYPE_PREFIX = 'urn:pulseboard:problem:';
const MAX_RETRY_AFTER_SECONDS = 3600;
const MAX_FIELD_ERRORS = 50;

function asRecord(value: unknown): Record<string, unknown> | null {
  if (typeof value === 'string') {
    try {
      return asRecord(JSON.parse(value));
    } catch {
      return null;
    }
  }
  return typeof value === 'object' && value !== null && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : null;
}

function text(value: unknown): string | undefined {
  return typeof value === 'string' && value.length > 0 ? value : undefined;
}

export function parseRetryAfter(header: string | null): number | undefined {
  const trimmed = header?.trim() ?? '';
  if (!/^\d{1,7}$/.test(trimmed)) {
    return undefined;
  }
  const seconds = Number(trimmed);
  return seconds > 0 ? Math.min(seconds, MAX_RETRY_AFTER_SECONDS) : undefined;
}

/** Turns any HttpClient failure into an ApiProblem. The body is never trusted to have the documented shape. */
export function decodeProblem(response: HttpErrorResponse): ApiProblem {
  const body = asRecord(response.error);
  const type = text(body?.['type']);
  const rawErrors = body?.['errors'];
  const fieldErrors: ProblemFieldError[] = [];
  if (Array.isArray(rawErrors)) {
    for (const entry of rawErrors.slice(0, MAX_FIELD_ERRORS)) {
      const item = asRecord(entry);
      const field = text(item?.['field']);
      const message = text(item?.['message']);
      if (field !== undefined && message !== undefined) {
        fieldErrors.push({ field, message });
      }
    }
  }
  return {
    status: response.status,
    kind: type?.startsWith(TYPE_PREFIX) ? type.slice(TYPE_PREFIX.length) : 'unknown',
    title: text(body?.['title']) ?? (response.status === 0 ? 'Network error' : 'Request failed'),
    detail: text(body?.['detail']),
    fieldErrors,
    retryAfterSeconds: parseRetryAfter(response.headers?.get('Retry-After') ?? null),
  };
}

function formatWait(seconds: number): string {
  if (seconds < 60) {
    return seconds === 1 ? '1 second' : `${seconds} seconds`;
  }
  const minutes = Math.ceil(seconds / 60);
  return minutes === 1 ? '1 minute' : `${minutes} minutes`;
}

/**
 * User facing text for a problem. It is composed here from the status, so server detail strings
 * (which may name internals) are never shown.
 */
export function describeProblem(problem: ApiProblem): string {
  const { status, retryAfterSeconds } = problem;
  if (status === 0) {
    return 'Cannot reach Pulseboard. Check your connection and try again.';
  }
  if (status === 429) {
    return retryAfterSeconds === undefined
      ? 'Too many requests. Wait a moment and try again.'
      : `Too many requests. Try again in ${formatWait(retryAfterSeconds)}.`;
  }
  if (status === 503) {
    return retryAfterSeconds === undefined
      ? 'Pulseboard is temporarily unavailable. Try again shortly.'
      : `Pulseboard is temporarily unavailable. Try again in ${formatWait(retryAfterSeconds)}.`;
  }
  if (status >= 500) {
    return 'Something went wrong on our side. Try again shortly.';
  }
  switch (status) {
    case 401:
      return 'Your session has ended. Sign in again.';
    case 403:
      return 'Your role does not allow this action.';
    case 404:
      return 'That item no longer exists.';
    case 409:
      return 'That conflicts with something that already exists.';
    case 400:
    case 422:
      return 'Some of the information is not valid.';
    default:
      return 'The request could not be completed.';
  }
}
