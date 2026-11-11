import { computed, inject } from '@angular/core';
import {
  patchState,
  signalStore,
  withComputed,
  withHooks,
  withMethods,
  withState,
} from '@ngrx/signals';
import { Subject, catchError, firstValueFrom, map, of, switchMap } from 'rxjs';

import { ApiClient } from '../../api/api-client';
import { ApiProblem, isApiError } from '../../api/problem';
import { quietContext } from '../../core/http/api-request';
import { SessionLifecycle } from '../../core/session/session-lifecycle';
import { SessionStore } from '../../core/session/session.store';
import {
  AlertRule,
  AlertRuleRequest,
  AlertRuleUpdateRequest,
  describeRuleProblem,
} from './alert-models';

/** How many deleted ids and confirmed versions are remembered; the oldest are forgotten first. */
export const MEMORY_LIMIT = 100;

/** Adds to a bounded, insertion ordered map. */
function remember<V>(memory: Map<string, V>, key: string, value: V): void {
  memory.delete(key);
  memory.set(key, value);
  while (memory.size > MEMORY_LIMIT) {
    const oldest = memory.keys().next();
    if (oldest.done) {
      return;
    }
    memory.delete(oldest.value);
  }
}

export type RulesStatus = 'idle' | 'loading' | 'loaded' | 'error';

/** A message about the last change attempt. `changed` offers the reload action. */
export interface RuleNotice {
  readonly kind: 'error' | 'changed';
  readonly message: string;
}

export type SaveResult =
  | { readonly ok: true; readonly rule: AlertRule }
  | { readonly ok: false; readonly problem: ApiProblem };

interface RulesState {
  status: RulesStatus;
  rules: readonly AlertRule[];
  problem: ApiProblem | null;
  /** Rules with a change on their way; their controls are inert. */
  pendingIds: readonly string[];
  notice: RuleNotice | null;
}

const INITIAL: RulesState = {
  status: 'idle',
  rules: [],
  problem: null,
  pendingIds: [],
  notice: null,
};

const UNKNOWN_PROBLEM: ApiProblem = {
  status: 0,
  kind: 'unknown',
  title: 'Request failed',
  fieldErrors: [],
};

const ROLES_THAT_MANAGE: readonly string[] = ['OWNER', 'ADMIN'];

function problemOf(error: unknown): ApiProblem {
  return isApiError(error) ? error.problem : UNKNOWN_PROBLEM;
}

/** The server no longer has the rule, or has a newer version of it: the list on screen is out of date. */
function isOutOfDate(problem: ApiProblem): boolean {
  return problem.kind === 'version-conflict' || problem.status === 404;
}

/**
 * Alert rules of the workspace.
 *
 * - Toggle and delete are optimistic and roll back on failure; create and update wait for the server
 *   (a new rule needs its id, and an edit may be refused).
 * - Every change carries the version it was based on. A version conflict (or a rule that vanished)
 *   never overwrites anything: the store keeps the old state and raises a "changed elsewhere" notice
 *   with a reload action.
 * - A list response that was requested before a change finished is dropped and requested again, and
 *   responses that arrive after logout are ignored (epoch).
 * - Deleted rules are remembered (a bounded set), and so is the newest version the server confirmed
 *   for a rule, so an older list answer arriving later cannot bring a deleted row or an old version back.
 */
export const AlertRulesStore = signalStore(
  { providedIn: 'root' },
  withState<RulesState>(INITIAL),
  withComputed(() => {
    const session = inject(SessionStore);
    return {
      /** Only OWNER and ADMIN may change rules; the API enforces the same. */
      canManage: computed(() => ROLES_THAT_MANAGE.includes(session.user()?.role ?? '')),
    };
  }),
  withMethods((store) => {
    const api = inject(ApiClient);
    // Bumped by reset(): replies from before it are dropped.
    let epoch = 0;
    // Bumped whenever a change finishes: a list requested earlier may not contain it.
    let changes = 0;
    // Rules that are deleted or being deleted, and the newest version the server confirmed per rule.
    const gone = new Map<string, true>();
    const confirmed = new Map<string, AlertRule>();
    const loads = new Subject<void>();

    /** A list answer without what is known to be newer than it. */
    const reconcile = (list: readonly AlertRule[]): readonly AlertRule[] =>
      list
        .filter((rule) => !gone.has(rule.id))
        .map((rule) => {
          const known = confirmed.get(rule.id);
          return known !== undefined && known.version > rule.version ? known : rule;
        });

    const setPending = (id: string, on: boolean): void =>
      patchState(store, (state) => ({
        pendingIds: on
          ? [...state.pendingIds.filter((entry) => entry !== id), id]
          : state.pendingIds.filter((entry) => entry !== id),
      }));

    const replace = (rule: AlertRule): void =>
      patchState(store, (state) => ({
        rules: state.rules.map((entry) => (entry.id === rule.id ? rule : entry)),
      }));

    loads
      .pipe(
        map(() => ({ startedIn: epoch, changesAtStart: changes })),
        switchMap((started) =>
          api.get('/api/v1/alert-rules', { context: quietContext() }).pipe(
            map((rules) => ({ ...started, rules, problem: null as ApiProblem | null })),
            catchError((error: unknown) =>
              of({ ...started, rules: null, problem: problemOf(error) }),
            ),
          ),
        ),
      )
      .subscribe((result) => {
        if (result.startedIn !== epoch) {
          return;
        }
        if (result.changesAtStart !== changes) {
          // A change finished while this list was on its way; it may be missing from it.
          loads.next();
          return;
        }
        if (result.rules === null) {
          patchState(store, { status: 'error', problem: result.problem });
          return;
        }
        patchState(store, {
          status: 'loaded',
          rules: reconcile(result.rules),
          problem: null,
          notice: null,
        });
      });

    const fail = (error: unknown, action: 'save' | 'toggle' | 'delete'): void => {
      const problem = problemOf(error);
      patchState(store, {
        notice: {
          kind: isOutOfDate(problem) ? 'changed' : 'error',
          message: describeRuleProblem(problem, action),
        },
      });
    };

    const load = (): void => {
      patchState(store, { status: 'loading', problem: null });
      loads.next();
    };

    return {
      /** Loads the list. Calling it again supersedes a load still on its way. */
      load,

      /** Loads the list unless it is loaded or on its way. */
      ensureLoaded(): void {
        if (store.status() === 'idle' || store.status() === 'error') {
          load();
        }
      },

      dismissNotice(): void {
        patchState(store, { notice: null });
      },

      /** Enables or disables a rule at once; the server's answer replaces it, a refusal restores it. */
      async toggle(rule: AlertRule, enabled: boolean): Promise<boolean> {
        if (!store.canManage() || store.pendingIds().includes(rule.id)) {
          return false;
        }
        const startedIn = epoch;
        setPending(rule.id, true);
        patchState(store, { notice: null });
        replace({ ...rule, enabled });
        try {
          const saved = await firstValueFrom(
            api.patch('/api/v1/alert-rules/{id}', {
              path: { id: rule.id },
              body: { enabled, version: rule.version },
              context: quietContext(),
            }),
          );
          if (startedIn === epoch) {
            changes += 1;
            remember(confirmed, saved.id, saved);
            replace(saved);
          }
          return true;
        } catch (error) {
          if (startedIn === epoch) {
            replace(rule);
            fail(error, 'toggle');
          }
          return false;
        } finally {
          if (startedIn === epoch) {
            setPending(rule.id, false);
          }
        }
      },

      /** Removes a rule at once; a refusal puts it back where it was. */
      async remove(rule: AlertRule): Promise<boolean> {
        if (!store.canManage() || store.pendingIds().includes(rule.id)) {
          return false;
        }
        const startedIn = epoch;
        const index = store.rules().findIndex((entry) => entry.id === rule.id);
        setPending(rule.id, true);
        remember(gone, rule.id, true);
        patchState(store, (state) => ({
          notice: null,
          rules: state.rules.filter((entry) => entry.id !== rule.id),
        }));
        try {
          await firstValueFrom(
            api.delete('/api/v1/alert-rules/{id}', {
              path: { id: rule.id },
              context: quietContext(),
            }),
          );
          if (startedIn === epoch) {
            changes += 1;
          }
          return true;
        } catch (error) {
          if (startedIn !== epoch) {
            return false;
          }
          if (isApiError(error) && error.problem.status === 404) {
            // Somebody else already deleted it: the wanted state is reached.
            changes += 1;
            return true;
          }
          gone.delete(rule.id);
          patchState(store, (state) => {
            if (state.rules.some((entry) => entry.id === rule.id)) {
              return {};
            }
            const rules = [...state.rules];
            rules.splice(Math.min(Math.max(index, 0), rules.length), 0, rule);
            return { rules };
          });
          fail(error, 'delete');
          return false;
        } finally {
          if (startedIn === epoch) {
            setPending(rule.id, false);
          }
        }
      },

      async create(request: AlertRuleRequest): Promise<SaveResult> {
        const startedIn = epoch;
        try {
          const rule = await firstValueFrom(
            api.post('/api/v1/alert-rules', { body: request, context: quietContext() }),
          );
          if (startedIn === epoch) {
            changes += 1;
            gone.delete(rule.id);
            remember(confirmed, rule.id, rule);
            patchState(store, (state) => ({
              rules: [...state.rules.filter((entry) => entry.id !== rule.id), rule],
            }));
          }
          return { ok: true, rule };
        } catch (error) {
          return { ok: false, problem: problemOf(error) };
        }
      },

      /** Replaces a rule. `request.version` is the version the edit started from. */
      async update(id: string, request: AlertRuleUpdateRequest): Promise<SaveResult> {
        const startedIn = epoch;
        try {
          const rule = await firstValueFrom(
            api.put('/api/v1/alert-rules/{id}', {
              path: { id },
              body: request,
              context: quietContext(),
            }),
          );
          if (startedIn === epoch) {
            changes += 1;
            remember(confirmed, rule.id, rule);
            replace(rule);
          }
          return { ok: true, rule };
        } catch (error) {
          return { ok: false, problem: problemOf(error) };
        }
      },

      reset(): void {
        epoch += 1;
        gone.clear();
        confirmed.clear();
        patchState(store, INITIAL);
      },
    };
  }),
  withHooks({
    onInit(store) {
      inject(SessionLifecycle).onClear(() => store.reset());
    },
  }),
);
