import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { errorInterceptor } from '../../core/http/interceptors';
import { SessionLifecycle } from '../../core/session/session-lifecycle';
import { SessionStore } from '../../core/session/session.store';
import { settle } from '../../core/testing/auth-testing';
import { fakeSession } from '../anomalies/anomaly-testing';
import { AlertRule } from './alert-models';
import { AlertRulesStore } from './alert-rules.store';
import { METRIC_ID, rawRule, ruleProblem } from './alert-testing';

function setup(role: string | null = 'OWNER') {
  TestBed.configureTestingModule({
    providers: [
      provideHttpClient(withInterceptors([errorInterceptor])),
      provideHttpClientTesting(),
      { provide: SessionStore, useValue: fakeSession(role) },
    ],
  });
  return {
    store: TestBed.inject(AlertRulesStore),
    http: TestBed.inject(HttpTestingController),
    lifecycle: TestBed.inject(SessionLifecycle),
  };
}

const LIST = '/api/v1/alert-rules';
const one = (rule: AlertRule) => `/api/v1/alert-rules/${rule.id}`;

async function loaded(rules: AlertRule[], role = 'OWNER') {
  const env = setup(role);
  env.store.load();
  env.http.expectOne(LIST).flush(rules);
  await settle();
  return env;
}

const REQUEST = {
  name: 'New rule',
  metricId: METRIC_ID,
  condition: 'THRESHOLD',
  operator: 'GT',
  threshold: 1,
  cooldownSeconds: 300,
  channel: 'EMAIL_LOG',
  target: 'oncall@example.com',
  enabled: true,
} as const;

describe('AlertRulesStore loading', () => {
  it('is idle, then loading, then loaded', () => {
    const { store, http } = setup();
    expect(store.status()).toBe('idle');
    store.load();
    expect(store.status()).toBe('loading');
    http.expectOne(LIST).flush([rawRule()]);
    expect(store.status()).toBe('loaded');
    expect(store.rules()).toHaveLength(1);
  });

  it('keeps an empty answer distinct from an error', () => {
    const { store, http } = setup();
    store.load();
    http.expectOne(LIST).flush([]);
    expect(store.status()).toBe('loaded');
    expect(store.rules()).toEqual([]);
    expect(store.problem()).toBeNull();
  });

  it('reports a failed load with its problem and can load again', () => {
    const { store, http } = setup();
    store.load();
    http.expectOne(LIST).flush({}, { status: 500, statusText: 'Server Error' });
    expect(store.status()).toBe('error');
    expect(store.problem()?.status).toBe(500);

    store.ensureLoaded();
    expect(store.status()).toBe('loading');
    http.expectOne(LIST).flush([]);
    expect(store.status()).toBe('loaded');
  });

  it('does not load again while loaded or on its way', () => {
    const { store, http } = setup();
    store.ensureLoaded();
    store.ensureLoaded();
    http.expectOne(LIST).flush([]);
    store.ensureLoaded();
    http.expectNone(LIST);
  });

  it('cancels a load that a newer load replaced', () => {
    const { store, http } = setup();
    store.load();
    const first = http.expectOne(LIST);
    store.load();
    expect(first.cancelled).toBe(true);
    http.expectOne(LIST).flush([rawRule({ name: 'newer' })]);
    expect(store.rules()[0].name).toBe('newer');
  });
});

describe('AlertRulesStore toggle', () => {
  it('shows the new state at once and keeps the server answer', async () => {
    const rule = rawRule({ enabled: true, version: 3 });
    const { store, http } = await loaded([rule]);

    const done = store.toggle(rule, false);
    expect(store.rules()[0].enabled).toBe(false);
    expect(store.pendingIds()).toEqual([rule.id]);

    const request = http.expectOne(one(rule));
    expect(request.request.method).toBe('PATCH');
    expect(request.request.body).toEqual({ enabled: false, version: 3 });
    request.flush({ ...rule, enabled: false, version: 4 });
    await expect(done).resolves.toBe(true);

    expect(store.rules()[0]).toMatchObject({ enabled: false, version: 4 });
    expect(store.pendingIds()).toEqual([]);
  });

  it('rolls back and explains when the server refuses', async () => {
    const rule = rawRule({ enabled: true });
    const { store, http } = await loaded([rule]);

    const done = store.toggle(rule, false);
    http.expectOne(one(rule)).flush({}, { status: 500, statusText: 'Server Error' });

    await expect(done).resolves.toBe(false);
    expect(store.rules()[0].enabled).toBe(true);
    expect(store.pendingIds()).toEqual([]);
    expect(store.notice()).toMatchObject({ kind: 'error' });
  });

  it('on a version conflict restores the rule, overwrites nothing and offers a reload', async () => {
    const rule = rawRule({ enabled: true, version: 1 });
    const { store, http } = await loaded([rule]);

    const done = store.toggle(rule, false);
    http.expectOne(one(rule)).flush(...ruleProblem('version-conflict', 409));

    await expect(done).resolves.toBe(false);
    expect(store.rules()[0]).toEqual(rule);
    expect(store.notice()).toEqual({
      kind: 'changed',
      message: expect.stringMatching(/changed elsewhere/),
    });

    store.load();
    http.expectOne(LIST).flush([{ ...rule, enabled: false, version: 2 }]);
    expect(store.rules()[0]).toMatchObject({ enabled: false, version: 2 });
    expect(store.notice()).toBeNull();
  });

  it('treats a vanished rule as out of date', async () => {
    const rule = rawRule();
    const { store, http } = await loaded([rule]);
    const done = store.toggle(rule, false);
    http.expectOne(one(rule)).flush(...ruleProblem('not-found', 404));
    await done;
    expect(store.notice()?.kind).toBe('changed');
  });

  it('ignores a second toggle while the first is on its way', async () => {
    const rule = rawRule();
    const { store, http } = await loaded([rule]);
    const first = store.toggle(rule, false);
    await expect(store.toggle(rule, true)).resolves.toBe(false);
    http.expectOne(one(rule)).flush({ ...rule, enabled: false });
    await first;
    http.expectNone(one(rule));
  });

  it('does nothing for a viewer', async () => {
    const rule = rawRule();
    const { store, http } = await loaded([rule], 'VIEWER');
    expect(store.canManage()).toBe(false);
    await expect(store.toggle(rule, false)).resolves.toBe(false);
    await expect(store.remove(rule)).resolves.toBe(false);
    http.expectNone(one(rule));
    expect(store.rules()[0].enabled).toBe(true);
  });
});

describe('AlertRulesStore remove', () => {
  it('removes the rule at once and keeps it removed on success', async () => {
    const [a, b, c] = [rawRule({ name: 'a' }), rawRule({ name: 'b' }), rawRule({ name: 'c' })];
    const { store, http } = await loaded([a, b, c]);
    const done = store.remove(b);
    expect(store.rules().map((r) => r.name)).toEqual(['a', 'c']);
    const request = http.expectOne(one(b));
    expect(request.request.method).toBe('DELETE');
    request.flush(null, { status: 204, statusText: 'No Content' });
    await expect(done).resolves.toBe(true);
    expect(store.rules().map((r) => r.name)).toEqual(['a', 'c']);
  });

  it('puts the rule back at its place when the delete fails', async () => {
    const [a, b, c] = [rawRule({ name: 'a' }), rawRule({ name: 'b' }), rawRule({ name: 'c' })];
    const { store, http } = await loaded([a, b, c]);
    const done = store.remove(b);
    http.expectOne(one(b)).flush({}, { status: 500, statusText: 'Server Error' });
    await expect(done).resolves.toBe(false);
    expect(store.rules().map((r) => r.name)).toEqual(['a', 'b', 'c']);
    expect(store.notice()?.kind).toBe('error');
  });

  it('counts a rule that is already gone as deleted', async () => {
    const rule = rawRule();
    const { store, http } = await loaded([rule]);
    const done = store.remove(rule);
    http.expectOne(one(rule)).flush(...ruleProblem('not-found', 404));
    await expect(done).resolves.toBe(true);
    expect(store.rules()).toEqual([]);
    expect(store.notice()).toBeNull();
  });
});

describe('AlertRulesStore create and update', () => {
  it('adds the created rule after the existing ones', async () => {
    const existing = rawRule({ name: 'old' });
    const { store, http } = await loaded([existing]);
    const done = store.create(REQUEST);
    const request = http.expectOne(LIST);
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual(REQUEST);
    request.flush(rawRule({ name: 'New rule' }), { status: 201, statusText: 'Created' });
    const result = await done;
    expect(result.ok).toBe(true);
    expect(store.rules().map((r) => r.name)).toEqual(['old', 'New rule']);
  });

  it('returns the problem of a refused create and changes nothing', async () => {
    const { store, http } = await loaded([]);
    const done = store.create(REQUEST);
    http.expectOne(LIST).flush(...ruleProblem('alert-rule-limit', 409));
    const result = await done;
    expect(result).toMatchObject({ ok: false, problem: { kind: 'alert-rule-limit', status: 409 } });
    expect(store.rules()).toEqual([]);
  });

  it('replaces the rule with the saved version', async () => {
    const rule = rawRule({ version: 2 });
    const { store, http } = await loaded([rule]);
    const done = store.update(rule.id, { ...REQUEST, version: 2 });
    const request = http.expectOne(one(rule));
    expect(request.request.method).toBe('PUT');
    expect(request.request.body.version).toBe(2);
    request.flush({ ...rule, name: 'Renamed', version: 3 });
    await done;
    expect(store.rules()[0]).toMatchObject({ name: 'Renamed', version: 3 });
  });

  it('keeps the stored rule untouched on a version conflict', async () => {
    const rule = rawRule({ version: 2 });
    const { store, http } = await loaded([rule]);
    const done = store.update(rule.id, { ...REQUEST, version: 2 });
    http.expectOne(one(rule)).flush(...ruleProblem('version-conflict', 409));
    const result = await done;
    expect(result).toMatchObject({ ok: false, problem: { kind: 'version-conflict' } });
    expect(store.rules()[0]).toEqual(rule);
  });
});

describe('AlertRulesStore stale responses', () => {
  it('requests the list again when a change finished while it was on its way', async () => {
    const rule = rawRule({ enabled: true });
    const { store, http } = await loaded([rule]);

    store.load();
    const slowList = http.expectOne(LIST);

    const done = store.toggle(rule, false);
    http.expectOne(one(rule)).flush({ ...rule, enabled: false, version: 2 });
    await done;

    // The list was read before the change: it still says enabled, and must not win.
    slowList.flush([rule]);
    expect(store.rules()[0].enabled).toBe(false);
    http.expectOne(LIST).flush([{ ...rule, enabled: false, version: 2 }]);
    expect(store.rules()[0]).toMatchObject({ enabled: false, version: 2 });
    expect(store.status()).toBe('loaded');
  });

  it('drops a list that arrives after logout', () => {
    const { store, http, lifecycle } = setup();
    store.load();
    const pending = http.expectOne(LIST);
    lifecycle.clear();
    expect(store.status()).toBe('idle');
    pending.flush([rawRule()]);
    expect(store.rules()).toEqual([]);
    expect(store.status()).toBe('idle');
  });

  it('drops a change answer that arrives after logout', async () => {
    const rule = rawRule();
    const { store, http, lifecycle } = await loaded([rule]);
    const done = store.toggle(rule, false);
    const request = http.expectOne(one(rule));
    lifecycle.clear();
    request.flush({ ...rule, enabled: false });
    await done;
    expect(store.rules()).toEqual([]);
    expect(store.pendingIds()).toEqual([]);
  });

  it('forgets everything on logout', async () => {
    const { store, lifecycle } = await loaded([rawRule()]);
    lifecycle.clear();
    expect(store.rules()).toEqual([]);
    expect(store.status()).toBe('idle');
    expect(store.notice()).toBeNull();
  });
});
