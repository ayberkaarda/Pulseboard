import { HttpContext } from '@angular/common/http';

import type { paths } from './generated/schema';

/** Methods the client exposes. */
export type ApiMethod = 'get' | 'post' | 'put' | 'patch' | 'delete';

/** Public API paths that offer the method. Service to service routes are never reachable from the browser. */
export type ApiPath<M extends ApiMethod> = {
  [P in keyof paths & string]: P extends `/internal/${string}`
    ? never
    : paths[P] extends Record<M, infer O>
      ? [NonNullable<O>] extends [never]
        ? never
        : P
      : never;
}[keyof paths & string];

type Operation<P extends keyof paths, M extends ApiMethod> =
  paths[P] extends Record<M, infer O> ? O : never;

type PathArgs<O> = O extends { parameters: { path: infer T } } ? T : never;
type QueryArgs<O> = O extends { parameters: { query?: infer Q } } ? NonNullable<Q> : never;
type BodyArgs<O> = O extends { requestBody: { content: { 'application/json': infer B } } }
  ? B
  : never;

type SuccessKey = 200 | 201 | 202;

type SuccessBodies<R> = {
  [K in keyof R & SuccessKey]: R[K] extends { content: { 'application/json': infer B } }
    ? B
    : never;
}[keyof R & SuccessKey];

/** The JSON document of the first success response, or void when there is none (for example 204). */
type ResponseBody<O> = O extends { responses: infer R }
  ? [SuccessBodies<R>] extends [never]
    ? undefined
    : SuccessBodies<R>
  : undefined;

type IsRequired<T> = [T] extends [never] ? false : object extends T ? false : true;

interface CommonOptions {
  /** Tags the request, for example to opt out of the global error toast. */
  readonly context?: HttpContext;
}

type OptionsOf<O> = CommonOptions &
  ([PathArgs<O>] extends [never] ? unknown : { readonly path: PathArgs<O> }) &
  ([QueryArgs<O>] extends [never] ? unknown : { readonly query?: QueryArgs<O> }) &
  ([BodyArgs<O>] extends [never] ? unknown : { readonly body: BodyArgs<O> });

type NeedsOptions<O> =
  IsRequired<PathArgs<O>> extends true
    ? true
    : IsRequired<QueryArgs<O>> extends true
      ? true
      : IsRequired<BodyArgs<O>> extends true
        ? true
        : false;

/** Options are a required argument only when the operation needs a path parameter, a query or a body. */
export type OptionsArg<P extends keyof paths, M extends ApiMethod> =
  NeedsOptions<Operation<P, M>> extends true
    ? [options: OptionsOf<Operation<P, M>>]
    : [options?: OptionsOf<Operation<P, M>>];

export type ResponseOf<P extends keyof paths, M extends ApiMethod> = ResponseBody<Operation<P, M>>;
