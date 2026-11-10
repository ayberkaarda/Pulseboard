import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { render, screen, within } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';

import { errorInterceptor } from '../../core/http/interceptors';
import { StubPage } from '../../core/testing/auth-testing';
import { SessionStore } from '../../core/session/session.store';
import { AnomalyBus } from '../anomalies/anomaly-bus';
import { anomaly, fakeSession, rawAnomaly } from '../anomalies/anomaly-testing';
import { OverviewPage } from './overview.page';

const CHECKOUT = '11111111-1111-4111-8111-111111111111';
const PAYMENTS = '22222222-2222-4222-8222-222222222222';
const EMPTY = '33333333-3333-4333-8333-333333333333';

const service = (id: string, name: string) => ({
  id,
  name,
  environment: 'PROD',
  createdAt: '2026-01-01T00:00:00Z',
});

const metric = (serviceId: string, n: number) => ({
  id: `00000000-0000-4000-8000-0000000000${serviceId.slice(0, 1)}${n}`,
  serviceId,
  name: `metric-${n}`,
  unit: 'ms',
  aggregation: 'AVG',
  createdAt: '2026-01-01T00:00:00Z',
});

const minutesAgo = (minutes: number) => new Date(Date.now() - minutes * 60_000).toISOString();

async function renderOverview() {
  const view = await render(OverviewPage, {
    providers: [
      provideHttpClient(withInterceptors([errorInterceptor])),
      provideHttpClientTesting(),
      provideRouter([{ path: 'app/services/:id', component: StubPage }]),
      // No token: the live stream stays off, so the test drives events through the bus.
      { provide: SessionStore, useValue: fakeSession('OWNER', { token: null }) },
    ],
  });
  return { ...view, http: TestBed.inject(HttpTestingController), bus: TestBed.inject(AnomalyBus) };
}

type Http = HttpTestingController;

const anomaliesRequests = (http: Http) => http.match((req) => req.url === '/api/v1/anomalies');

function flushBoard(
  http: Http,
  anomalies: object[] = [],
  services = [
    service(CHECKOUT, 'checkout'),
    service(PAYMENTS, 'payments'),
    service(EMPTY, 'search'),
  ],
) {
  http.expectOne('/api/v1/services').flush(services);
  const requests = anomaliesRequests(http);
  expect(requests).toHaveLength(1);
  requests[0].flush({ items: anomalies, totalItems: anomalies.length, totalPages: 1 });
}

function flushMetrics(http: Http) {
  http
    .expectOne(`/api/v1/services/${CHECKOUT}/metrics`)
    .flush([metric(CHECKOUT, 1), metric(CHECKOUT, 2)]);
  http.expectOne(`/api/v1/services/${PAYMENTS}/metrics`).flush([metric(PAYMENTS, 1)]);
  http.expectOne(`/api/v1/services/${EMPTY}/metrics`).flush([]);
}

function tile(name: string): HTMLElement {
  return within(screen.getByRole('list', { name: 'Services' })).getByRole('link', {
    name: new RegExp(name),
  });
}

describe('OverviewPage', () => {
  it('shows a loading status, then the services', async () => {
    const { http } = await renderOverview();
    expect(screen.getByText('Loading services')).toBeInTheDocument();

    flushBoard(http);
    await screen.findByRole('list', { name: 'Services' });
    expect(tile('checkout')).toHaveAttribute('href', `/app/services/${CHECKOUT}`);
  });

  it('derives each health from unacknowledged anomalies of the last hour and metrics', async () => {
    const { http } = await renderOverview();
    flushBoard(http, [
      rawAnomaly({ serviceId: CHECKOUT, severity: 'HIGH', windowStart: minutesAgo(5) }),
      rawAnomaly({ serviceId: CHECKOUT, severity: 'LOW', windowStart: minutesAgo(9) }),
      rawAnomaly({ serviceId: PAYMENTS, severity: 'MEDIUM', windowStart: minutesAgo(20) }),
      rawAnomaly({
        serviceId: PAYMENTS,
        severity: 'HIGH',
        windowStart: minutesAgo(10),
        acknowledgedAt: minutesAgo(2),
      }),
    ]);
    await screen.findByRole('list', { name: 'Services' });
    flushMetrics(http);

    await within(tile('checkout')).findByText('Down');
    expect(tile('checkout')).toHaveTextContent('2 open anomalies in the last hour');
    expect(tile('checkout')).toHaveTextContent('Highest severity: High');
    expect(tile('payments')).toHaveTextContent('Degraded');
    expect(tile('payments')).toHaveTextContent('1 open anomaly in the last hour');
    expect(tile('search')).toHaveTextContent('No data');
    expect(tile('search')).toHaveTextContent('No metrics defined yet');

    const summary = screen.getByRole('list', { name: 'Services by health' });
    expect(summary).toHaveTextContent(/0\s*Healthy/);
    expect(summary).toHaveTextContent(/1\s*Degraded/);
    expect(summary).toHaveTextContent(/1\s*Down/);
    expect(summary).toHaveTextContent(/1\s*No data/);

    const names = within(screen.getByRole('list', { name: 'Services' }))
      .getAllByRole('link')
      .map((link) => link.querySelector('.name')?.textContent);
    expect(names).toEqual(['checkout', 'payments', 'search']);
  });

  it('shows a service with metrics and nothing open as healthy', async () => {
    const { http } = await renderOverview();
    flushBoard(http, [], [service(CHECKOUT, 'checkout')]);
    await screen.findByRole('list', { name: 'Services' });
    http.expectOne(`/api/v1/services/${CHECKOUT}/metrics`).flush([metric(CHECKOUT, 1)]);

    await within(tile('checkout')).findByText('Healthy');
    expect(tile('checkout')).toHaveTextContent('No open anomalies in the last hour');
  });

  it('uses one anomalies request for every service, not one per service', async () => {
    const { http } = await renderOverview();
    flushBoard(http);
    await screen.findByRole('list', { name: 'Services' });
    flushMetrics(http);
    expect(anomaliesRequests(http)).toHaveLength(0);
  });

  it('moves a tile when a live anomaly arrives and back when it is acknowledged', async () => {
    const { http, bus } = await renderOverview();
    flushBoard(http);
    await screen.findByRole('list', { name: 'Services' });
    flushMetrics(http);
    await within(tile('payments')).findByText('Healthy');

    const live = anomaly({ serviceId: PAYMENTS, severity: 'HIGH', windowStart: minutesAgo(0) });
    bus.publish(live, 'event');
    await within(tile('payments')).findByText('Down');

    bus.publish({ ...live, acknowledgedAt: minutesAgo(0), acknowledgedBy: 'u' });
    await within(tile('payments')).findByText('Healthy');

    bus.publish(live, 'rollback');
    await within(tile('payments')).findByText('Down');
  });

  it('does not call a service healthy while the anomalies of the hour are unknown', async () => {
    const { http } = await renderOverview();
    http.expectOne('/api/v1/services').flush([service(CHECKOUT, 'checkout')]);
    anomaliesRequests(http)[0].flush(
      { type: 'urn:pulseboard:problem:internal-error' },
      { status: 500, statusText: 'x' },
    );
    await screen.findByRole('list', { name: 'Services' });
    http.expectOne(`/api/v1/services/${CHECKOUT}/metrics`).flush([metric(CHECKOUT, 1)]);

    expect(await screen.findByText('Could not read recent anomalies')).toBeInTheDocument();
    expect(tile('checkout')).toHaveTextContent('No data');
    expect(tile('checkout')).not.toHaveTextContent('Healthy');
  });

  it('never calls a service healthy when the anomaly result was cut off', async () => {
    const { http } = await renderOverview();
    http
      .expectOne('/api/v1/services')
      .flush([service(CHECKOUT, 'checkout'), service(PAYMENTS, 'payments')]);
    const [first] = anomaliesRequests(http);
    first.flush({
      items: [rawAnomaly({ serviceId: CHECKOUT, severity: 'HIGH', windowStart: minutesAgo(5) })],
      totalItems: 650,
      totalPages: 7,
    });
    // Five pages are read at most; the rest of the hour is never seen.
    const rest = anomaliesRequests(http);
    expect(rest).toHaveLength(4);
    rest.forEach((request) => request.flush({ items: [], totalItems: 650, totalPages: 7 }));
    await screen.findByRole('list', { name: 'Services' });
    http.expectOne(`/api/v1/services/${CHECKOUT}/metrics`).flush([metric(CHECKOUT, 1)]);
    http.expectOne(`/api/v1/services/${PAYMENTS}/metrics`).flush([metric(PAYMENTS, 1)]);

    await within(tile('checkout')).findByText('Down');
    expect(within(tile('payments')).queryByText('Healthy')).not.toBeInTheDocument();
    expect(within(tile('payments')).getByText('No data')).toBeInTheDocument();
    expect(tile('payments')).toHaveTextContent('health cannot be judged');
  });

  it('explains that nothing is reporting yet', async () => {
    const { http } = await renderOverview();
    flushBoard(http, [], []);
    expect(
      await screen.findByRole('heading', { name: 'No services reporting yet' }),
    ).toBeInTheDocument();
  });

  it('announces a failure in plain words and retries on request', async () => {
    const user = userEvent.setup();
    const { http } = await renderOverview();
    http
      .expectOne('/api/v1/services')
      .flush(
        { type: 'urn:pulseboard:problem:internal-error', detail: 'NullPointerException at Foo' },
        { status: 500, statusText: 'x' },
      );
    anomaliesRequests(http)[0].flush({ items: [] });

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Something went wrong on our side. Try again shortly.');
    expect(alert).not.toHaveTextContent('NullPointer');

    await user.click(screen.getByRole('button', { name: 'Try again' }));
    flushBoard(http, [], [service(CHECKOUT, 'checkout')]);

    expect(await screen.findByRole('link', { name: /checkout/ })).toBeInTheDocument();
  });
});
