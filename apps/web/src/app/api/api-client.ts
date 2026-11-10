import { HttpClient, HttpContext, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import type { ApiMethod, ApiPath, OptionsArg, ResponseOf } from './api-types';

interface RuntimeOptions {
  readonly path?: Readonly<Record<string, unknown>>;
  readonly query?: Readonly<Record<string, unknown>>;
  readonly body?: unknown;
  readonly context?: HttpContext;
}

/** Fills `{name}` placeholders with encoded values. A missing value is a programming error. */
export function buildUrl(template: string, params: Readonly<Record<string, unknown>> = {}): string {
  return template.replace(/\{([^}]+)\}/g, (_match, name: string) => {
    const value = params[name];
    if (value === undefined || value === null || value === '') {
      throw new Error(`Missing path parameter "${name}" for ${template}`);
    }
    return encodeURIComponent(String(value));
  });
}

export function buildParams(query: Readonly<Record<string, unknown>> = {}): HttpParams {
  let params = new HttpParams();
  for (const [key, value] of Object.entries(query)) {
    if (value === undefined || value === null) {
      continue;
    }
    for (const item of Array.isArray(value) ? value : [value]) {
      params = params.append(key, String(item));
    }
  }
  return params;
}

/**
 * Typed access to the REST API. Paths, path parameters, query, request bodies and response bodies
 * come from the schema in `./generated/schema`; there is no per-endpoint code to maintain.
 * Failures arrive as `ApiError` (see the error interceptor).
 */
@Injectable({ providedIn: 'root' })
export class ApiClient {
  private readonly http = inject(HttpClient);

  get<P extends ApiPath<'get'>>(
    path: P,
    ...args: OptionsArg<P, 'get'>
  ): Observable<ResponseOf<P, 'get'>> {
    return this.send<ResponseOf<P, 'get'>>('GET', path, args[0]);
  }

  post<P extends ApiPath<'post'>>(
    path: P,
    ...args: OptionsArg<P, 'post'>
  ): Observable<ResponseOf<P, 'post'>> {
    return this.send<ResponseOf<P, 'post'>>('POST', path, args[0]);
  }

  delete<P extends ApiPath<'delete'>>(
    path: P,
    ...args: OptionsArg<P, 'delete'>
  ): Observable<ResponseOf<P, 'delete'>> {
    return this.send<ResponseOf<P, 'delete'>>('DELETE', path, args[0]);
  }

  private send<T>(
    method: Uppercase<ApiMethod>,
    template: string,
    options: RuntimeOptions | undefined,
  ): Observable<T> {
    return this.http.request<T>(method, buildUrl(template, options?.path), {
      params: buildParams(options?.query),
      body: options?.body,
      context: options?.context,
      responseType: 'json',
    });
  }
}
