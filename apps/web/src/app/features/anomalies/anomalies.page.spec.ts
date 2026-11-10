import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ApplicationRef } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { render, screen, waitFor, within } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';

import { errorInterceptor } from '../../core/http/interceptors';
import { Notifier } from '../../core/notify/notifier.service';
import { StubPage } from '../../core/testing/auth-testing';
import { SessionStore } from '../../core/session/session.store';
import { AnomaliesPage } from './anomalies.page';
import { AnomalyBus } from './anomaly-bus';
import { anomaly, fakeSession, rawAnomaly } from './anomaly-testing';

const SERVICE = '11111111-1111-4111-8111-111111111111';
const METRIC = '22222222-2222-4222-8222-222222222222';

type Http = HttpTestingController;

async function renderPage(role: string | null = 'OWNER') {
  const notifier = { error: jest.fn(), info: jest.fn() };
  const view = await render(AnomaliesPage, {
    providers: [
      provideHttpClient(withInterceptors([errorInterceptor])),
      provideHttpClientTesting(),
      provideRouter([{ path: 'app/metrics/:id', component: StubPage }]),
      { provide: SessionStore, useValue: fakeSession(role, { token: null }) },
      { provide: Notifier, useValue: notifier },
    ],
  });
  return {
    ...view,
    notifier,
    http: TestBed.inject(HttpTestingController),
    bus: TestBed.inject(AnomalyBus),
    user: userEvent.setup(),
  };
}

const rows = () => within(screen.getByRole('list', { name: 'Anomalies' }));
const rowText = (text: string) => rows().getByText(text);
const queryRowText = (text: string) => rows().queryByText(text);
const findRowText = (text: string) => rows().findByText(text);
const list = (http: Http) => http.expectOne((req) => req.url === '/api/v1/anomalies');
const page = (items: object[], totalPages = 1, totalItems = items.length) => ({
  items,
  page: 0,
  size: 25,
  totalItems,
  totalPages,
});

/** Answers the three requests the page makes on opening. */
async function open(http: Http, items: object[], totalPages = 1, totalItems = items.length) {
  http
    .expectOne('/api/v1/services')
    .flush([
      { id: SERVICE, name: 'checkout', environment: 'PROD', createdAt: '2026-01-01T00:00:00Z' },
    ]);
  list(http).flush(page(items, totalPages, totalItems));
  // The effect that asks for metric names runs on the next change detection.
  TestBed.inject(ApplicationRef).tick();
  http
    .match((req) => req.url === `/api/v1/services/${SERVICE}/metrics`)
    .forEach((req) =>
      req.flush([
        {
          id: METRIC,
          serviceId: SERVICE,
          name: 'latency.p95',
          unit: 'ms',
          aggregation: 'AVG',
          createdAt: '2026-01-01T00:00:00Z',
        },
      ]),
    );
}

describe('AnomaliesPage', () => {
  it('shows a loading status, then the anomalies with severity as glyph and word', async () => {
    const { http } = await renderPage();
    expect(screen.getByText('Loading anomalies')).toBeInTheDocument();

    await open(http, [
      rawAnomaly({ severity: 'HIGH', windowStart: '2026-05-01T12:00:00Z', score: 0.9 }),
      rawAnomaly({ severity: 'LOW', windowStart: '2026-05-01T11:00:00Z' }),
    ]);

    const rows = await screen.findAllByRole('listitem');
    expect(rows).toHaveLength(2);
    expect(within(rows[0]).getByText('High')).toBeInTheDocument();
    expect(within(rows[1]).getByText('Low')).toBeInTheDocument();
    expect(within(rows[0]).getByText(/Z-score, score 0.90/)).toBeInTheDocument();
  });

  it('names the metric and links to its page with the service', async () => {
    const { http } = await renderPage();
    await open(http, [rawAnomaly({ metricId: METRIC, serviceId: SERVICE })]);

    const link = await screen.findByRole('link', { name: 'latency.p95' });
    expect(link).toHaveAttribute('href', `/app/metrics/${METRIC}?service=${SERVICE}`);
    expect(
      within(screen.getByRole('list', { name: 'Anomalies' })).getByText('checkout'),
    ).toBeInTheDocument();
  });

  it('shows the exact time in the title of the relative time', async () => {
    const { http } = await renderPage();
    await open(http, [rawAnomaly({ windowStart: '2026-05-01T12:00:00Z' })]);

    const time = await screen.findByRole('time', {}).catch(() => null);
    const element = time ?? document.querySelector('time');
    expect(element).toHaveAttribute('datetime', '2026-05-01T12:00:00Z');
    expect(element?.getAttribute('title')).toMatch(/2026/);
    expect(element?.textContent).toMatch(/ago/);
  });

  it('asks the server for the chosen filters', async () => {
    const { http, user } = await renderPage();
    await open(http, [rawAnomaly()]);
    await screen.findAllByRole('listitem');

    await user.selectOptions(screen.getByLabelText('Severity'), 'HIGH');
    const bySeverity = list(http);
    expect(bySeverity.request.params.get('severity')).toBe('HIGH');
    bySeverity.flush(page([rawAnomaly({ severity: 'HIGH' })]));

    await user.selectOptions(screen.getByLabelText('Status'), 'open');
    const byStatus = list(http);
    expect(byStatus.request.params.get('acknowledged')).toBe('false');
    expect(byStatus.request.params.get('severity')).toBe('HIGH');
    byStatus.flush(page([]));

    await user.selectOptions(screen.getByLabelText('Service'), SERVICE);
    const byService = list(http);
    expect(byService.request.params.get('serviceId')).toBe(SERVICE);
    byService.flush(page([]));
  });

  it('keeps the metric filter off until a service is chosen, then offers its metrics', async () => {
    const { http, user } = await renderPage();
    await open(http, [rawAnomaly({ metricId: METRIC, serviceId: SERVICE })]);
    await screen.findAllByRole('listitem');

    expect(screen.getByLabelText('Metric')).toBeDisabled();
    await user.selectOptions(screen.getByLabelText('Service'), SERVICE);
    list(http).flush(page([]));

    expect(screen.getByLabelText('Metric')).toBeEnabled();
    await user.selectOptions(screen.getByLabelText('Metric'), METRIC);
    const request = list(http);
    expect(request.request.params.get('metricId')).toBe(METRIC);
    request.flush(page([]));
  });

  it('explains an empty result of a filter and clears it', async () => {
    const { http, user } = await renderPage();
    await open(http, [rawAnomaly()]);
    await screen.findAllByRole('listitem');

    await user.selectOptions(screen.getByLabelText('Severity'), 'HIGH');
    list(http).flush(page([]));
    expect(
      await screen.findByRole('heading', { name: 'No anomalies match these filters' }),
    ).toBeInTheDocument();

    await user.click(screen.getAllByRole('button', { name: 'Clear filters' })[0]);
    const cleared = list(http);
    expect(cleared.request.params.has('severity')).toBe(false);
    cleared.flush(page([rawAnomaly()]));
    expect(await screen.findAllByRole('listitem')).toHaveLength(1);
  });

  it('says there is nothing yet when there are no filters and no anomalies', async () => {
    const { http } = await renderPage();
    await open(http, []);
    expect(await screen.findByRole('heading', { name: 'No anomalies yet' })).toBeInTheDocument();
  });

  it('announces a failure and loads again on request', async () => {
    const { http, user } = await renderPage();
    http.expectOne('/api/v1/services').flush([]);
    list(http).flush(
      { type: 'urn:pulseboard:problem:internal-error', detail: 'stack trace' },
      { status: 500, statusText: 'x' },
    );

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Something went wrong on our side. Try again shortly.');
    expect(alert).not.toHaveTextContent('stack trace');

    await user.click(screen.getByRole('button', { name: 'Try again' }));
    list(http).flush(page([rawAnomaly()]));
    expect(await screen.findAllByRole('listitem')).toHaveLength(1);
  });

  it('pages with Previous and Next', async () => {
    const { http, user } = await renderPage();
    await open(http, [rawAnomaly()], 3, 61);
    await screen.findAllByRole('listitem');

    const nav = screen.getByRole('navigation', { name: 'Pagination' });
    expect(nav).toHaveTextContent('Page 1 of 3, 61 in total');
    expect(within(nav).getByRole('button', { name: 'Previous' })).toBeDisabled();

    await user.click(within(nav).getByRole('button', { name: 'Next' }));
    const second = list(http);
    expect(second.request.params.get('page')).toBe('1');
    second.flush({ ...page([rawAnomaly()], 3, 61), page: 1 });

    expect(await screen.findByText(/Page 2 of 3/)).toBeInTheDocument();
    // The list was replaced while loading, so the navigation is a new element.
    await user.click(screen.getByRole('button', { name: 'Next' }));
    list(http).flush({ ...page([rawAnomaly()], 3, 61), page: 2 });
    expect(await screen.findByText(/Page 3 of 3/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Previous' })).toBeEnabled();
  });

  describe('acknowledge', () => {
    it('acknowledges at once and keeps it when the server agrees', async () => {
      const target = rawAnomaly({ severity: 'HIGH' });
      const { http, user } = await renderPage('OWNER');
      await open(http, [target]);

      const button = await screen.findByRole('button', { name: /^Acknowledge high anomaly/ });
      expect(button).toBeEnabled();
      await user.click(button);

      expect(rowText('Acknowledged')).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: /^Acknowledge/ })).not.toBeInTheDocument();
      http
        .expectOne({ method: 'POST', url: `/api/v1/anomalies/${target.id}/ack` })
        .flush({ ...target, acknowledgedAt: '2026-05-01T12:31:00Z', acknowledgedBy: 'user-1' });
      expect(await findRowText('Acknowledged')).toBeInTheDocument();
    });

    it('puts the button back and tells the person when the server refuses', async () => {
      const target = rawAnomaly();
      const { http, user, notifier } = await renderPage('ADMIN');
      await open(http, [target]);

      await user.click(await screen.findByRole('button', { name: /^Acknowledge/ }));
      http
        .expectOne(`/api/v1/anomalies/${target.id}/ack`)
        .flush({ type: 'urn:pulseboard:problem:forbidden' }, { status: 403, statusText: 'x' });

      expect(await screen.findByRole('button', { name: /^Acknowledge/ })).toBeEnabled();
      expect(queryRowText('Acknowledged')).not.toBeInTheDocument();
      expect(notifier.error).toHaveBeenCalledWith(
        'Only owners and admins can acknowledge anomalies.',
      );
    });

    it('does not let a viewer acknowledge, and says why', async () => {
      const target = rawAnomaly();
      const { http, user } = await renderPage('VIEWER');
      await open(http, [target]);

      const button = await screen.findByRole('button', { name: /^Acknowledge/ });
      expect(button).toHaveAttribute('aria-disabled', 'true');
      const note = screen.getByText('Only owners and admins can acknowledge anomalies.');
      expect(button.getAttribute('aria-describedby')).toBe(note.id);

      await user.click(button);
      http.expectNone((req) => req.url.endsWith('/ack'));
      expect(queryRowText('Acknowledged')).not.toBeInTheDocument();
    });

    it('does not show the viewer note to people who can acknowledge', async () => {
      const { http } = await renderPage('OWNER');
      await open(http, [rawAnomaly()]);
      await screen.findAllByRole('listitem');
      expect(
        screen.queryByText('Only owners and admins can acknowledge anomalies.'),
      ).not.toBeInTheDocument();
    });
  });

  describe('live updates', () => {
    it('adds a new anomaly on the first page without a request', async () => {
      const { http, bus } = await renderPage();
      await open(http, [rawAnomaly({ windowStart: '2026-05-01T10:00:00Z' })]);
      await screen.findAllByRole('listitem');

      bus.publish(anomaly({ severity: 'HIGH', windowStart: '2026-05-01T12:00:00Z' }), 'event');

      await waitFor(() => expect(screen.getAllByRole('listitem')).toHaveLength(2));
      expect(within(screen.getAllByRole('listitem')[0]).getByText('High')).toBeInTheDocument();
      http.expectNone((req) => req.url === '/api/v1/anomalies');
    });

    it('offers a button instead of moving the list when the person is on another page', async () => {
      const { http, bus, user } = await renderPage();
      await open(http, [rawAnomaly()], 2, 40);
      await screen.findAllByRole('listitem');
      await user.click(screen.getByRole('button', { name: 'Next' }));
      list(http).flush({ ...page([rawAnomaly({ id: 'only-on-page-2' })], 2, 40), page: 1 });
      await screen.findAllByRole('listitem');

      bus.publish(anomaly(), 'event');
      bus.publish(anomaly(), 'event');

      const button = await screen.findByRole('button', { name: /Show 2 new anomalies/ });
      expect(screen.getAllByRole('listitem')).toHaveLength(1);
      await user.click(button);
      expect(list(http).request.params.get('page')).toBe('0');
    });
  });
});
