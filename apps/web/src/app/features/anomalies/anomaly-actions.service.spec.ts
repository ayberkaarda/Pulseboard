import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { Anomaly } from '../../api/models';
import { errorInterceptor } from '../../core/http/interceptors';
import { Notifier } from '../../core/notify/notifier.service';
import { SessionStore } from '../../core/session/session.store';
import { AnomalyActions } from './anomaly-actions.service';
import { AnomalyBus } from './anomaly-bus';
import { anomaly, fakeSession, rawAnomaly } from './anomaly-testing';

function setup(role: string | null) {
  const notifier = { error: jest.fn(), info: jest.fn() };
  TestBed.configureTestingModule({
    providers: [
      provideHttpClient(withInterceptors([errorInterceptor])),
      provideHttpClientTesting(),
      provideRouter([]),
      { provide: SessionStore, useValue: fakeSession(role) },
      { provide: Notifier, useValue: notifier },
    ],
  });
  const seen: string[] = [];
  const states: Anomaly[] = [];
  TestBed.inject(AnomalyBus).changes.subscribe(({ anomaly: a, kind }) => {
    seen.push(`${kind}:${a.acknowledgedAt === null ? 'open' : 'acked'}`);
    states.push(a);
  });
  return {
    actions: TestBed.inject(AnomalyActions),
    http: TestBed.inject(HttpTestingController),
    notifier,
    seen,
    states,
  };
}

describe('AnomalyActions', () => {
  it.each([
    ['OWNER', true],
    ['ADMIN', true],
    ['VIEWER', false],
    ['MEMBER', false],
    [null, false],
  ])('role %s may acknowledge: %s', (role, allowed) => {
    expect(setup(role).actions.canAcknowledge()).toBe(allowed);
  });

  it('shows the acknowledgement at once and keeps the answer of the server', async () => {
    const { actions, http, seen, states } = setup('OWNER');
    const target = anomaly();

    const done = actions.acknowledge(target);
    expect(seen).toEqual(['update:acked']);
    expect(states[0].acknowledgedBy).toBe('user-1');
    expect(actions.pending().has(target.id)).toBe(true);

    http.expectOne({ method: 'POST', url: `/api/v1/anomalies/${target.id}/ack` }).flush(
      rawAnomaly({
        id: target.id,
        acknowledgedAt: '2026-05-01T12:31:00Z',
        acknowledgedBy: 'user-1',
      }),
    );

    await expect(done).resolves.toBe(true);
    expect(seen).toEqual(['update:acked', 'update:acked']);
    expect(states[1].acknowledgedAt).toBe('2026-05-01T12:31:00Z');
    expect(actions.pending().has(target.id)).toBe(false);
  });

  it.each([
    [403, 'Only owners and admins can acknowledge anomalies.'],
    [404, 'That anomaly no longer exists. Reload the list.'],
    [500, 'Something went wrong on our side. Try again shortly.'],
  ])('puts the open state back and says why when the server answers %i', async (status, text) => {
    const { actions, http, seen, notifier } = setup('OWNER');
    const target = anomaly();

    const done = actions.acknowledge(target);
    http
      .expectOne(`/api/v1/anomalies/${target.id}/ack`)
      .flush({ type: 'urn:pulseboard:problem:x' }, { status, statusText: 'x' });

    await expect(done).resolves.toBe(false);
    expect(seen).toEqual(['update:acked', 'rollback:open']);
    expect(notifier.error).toHaveBeenCalledWith(text);
    expect(actions.pending().has(target.id)).toBe(false);
  });

  it('rolls back when the connection fails', async () => {
    const { actions, http, seen, notifier } = setup('ADMIN');
    const done = actions.acknowledge(anomaly());
    http.expectOne((req) => req.url.endsWith('/ack')).error(new ProgressEvent('error'));

    await expect(done).resolves.toBe(false);
    expect(seen).toEqual(['update:acked', 'rollback:open']);
    expect(notifier.error).toHaveBeenCalled();
  });

  it('does nothing for a viewer: no request, no change', async () => {
    const { actions, http, seen } = setup('VIEWER');
    await expect(actions.acknowledge(anomaly())).resolves.toBe(false);
    http.expectNone((req) => req.url.endsWith('/ack'));
    expect(seen).toEqual([]);
  });

  it('sends one request when the button is pressed twice', async () => {
    const { actions, http } = setup('OWNER');
    const target = anomaly();
    const first = actions.acknowledge(target);
    const second = actions.acknowledge(target);

    http
      .expectOne(`/api/v1/anomalies/${target.id}/ack`)
      .flush(rawAnomaly({ id: target.id, acknowledgedAt: '2026-05-01T12:31:00Z' }));
    await expect(first).resolves.toBe(true);
    await expect(second).resolves.toBe(false);
  });

  it('skips an anomaly that is already acknowledged', async () => {
    const { actions, http } = setup('OWNER');
    await actions.acknowledge(anomaly({ acknowledgedAt: '2026-05-01T12:00:00Z' }));
    http.expectNone((req) => req.url.endsWith('/ack'));
  });
});
