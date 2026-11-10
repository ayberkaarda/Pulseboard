import { ApplicationRef, Component, input } from '@angular/core';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { render, screen, waitFor, within } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';

import { MetricPoint } from '../../api/models';
import { errorInterceptor } from '../../core/http/interceptors';
import { Notifier } from '../../core/notify/notifier.service';
import { SessionStore } from '../../core/session/session.store';
import { StubPage } from '../../core/testing/auth-testing';
import { fakeSession, rawAnomaly } from '../anomalies/anomaly-testing';
import { MetricChartComponent } from './metric-chart.component';
import { MetricDetailPage } from './metric-detail.page';

const SERVICE = '11111111-1111-4111-8111-111111111111';
const METRIC = '22222222-2222-4222-8222-222222222222';

// ECharts needs a canvas, which jsdom does not have. The chart is replaced by a stand-in that shows
// what it was given; the option builder has its own tests.
@Component({
  selector: 'app-metric-chart',
  template: `<div
    data-testid="chart"
    [attr.data-points]="points().length"
    [attr.data-anomalies]="anomalies().length"
  ></div>`,
})
class ChartStub {
  readonly points = input<readonly MetricPoint[]>([]);
  readonly anomalies = input<readonly unknown[]>([]);
  readonly fromMs = input(0);
  readonly toMs = input(0);
  readonly stepSeconds = input(0);
  readonly name = input('');
  readonly unit = input('');
  readonly aggregation = input<string | null>(null);
}

type Http = HttpTestingController;

async function renderPage(role = 'OWNER', inputs: Record<string, unknown> = { id: METRIC }) {
  TestBed.overrideComponent(MetricDetailPage, {
    remove: { imports: [MetricChartComponent] },
    add: { imports: [ChartStub] },
  });
  const notifier = { error: jest.fn(), info: jest.fn() };
  const view = await render(MetricDetailPage, {
    inputs,
    providers: [
      provideHttpClient(withInterceptors([errorInterceptor])),
      provideHttpClientTesting(),
      provideRouter([
        { path: 'app/overview', component: StubPage },
        { path: 'app/services/:id', component: StubPage },
      ]),
      { provide: SessionStore, useValue: fakeSession(role, { token: null }) },
      { provide: Notifier, useValue: notifier },
    ],
  });
  TestBed.inject(ApplicationRef).tick();
  return {
    ...view,
    notifier,
    http: TestBed.inject(HttpTestingController),
    user: userEvent.setup(),
  };
}

const points = (http: Http) =>
  http.expectOne((req) => req.url === `/api/v1/metrics/${METRIC}/points`);
const anomalies = (http: Http) => http.expectOne((req) => req.url === '/api/v1/anomalies');

function answerNames(http: Http) {
  http
    .expectOne('/api/v1/services')
    .flush([
      { id: SERVICE, name: 'checkout', environment: 'PROD', createdAt: '2026-01-01T00:00:00Z' },
    ]);
  http.expectOne(`/api/v1/metrics/${METRIC}`).flush({
    id: METRIC,
    serviceId: SERVICE,
    name: 'latency.p95',
    unit: 'ms',
    aggregation: 'AVG',
    createdAt: '2026-01-01T00:00:00Z',
  });
}

const series = (values: (number | null)[]) => ({
  metricId: METRIC,
  aggregation: 'AVG',
  step: 'PT5M',
  points: values.map((value, index) => ({
    ts: new Date(Date.UTC(2026, 4, 1, 11, index * 5)).toISOString(),
    value,
    count: 4,
  })),
});

describe('MetricDetailPage', () => {
  it('shows a loading status while the values are on their way', async () => {
    await renderPage();
    expect(screen.getByText('Loading values')).toBeInTheDocument();
  });

  it('draws the series with its anomalies and states the numbers as text', async () => {
    const { http } = await renderPage();
    answerNames(http);
    points(http).flush(series([10, 40, 12]));
    anomalies(http).flush({
      items: [rawAnomaly({ metricId: METRIC, severity: 'HIGH' })],
      totalItems: 1,
    });

    const chart = await screen.findByTestId('chart');
    expect(chart).toHaveAttribute('data-points', '3');
    expect(chart).toHaveAttribute('data-anomalies', '1');
    expect(screen.getByRole('heading', { level: 1, name: 'latency.p95' })).toBeInTheDocument();
    expect(screen.getByText('checkout, Average, ms')).toBeInTheDocument();

    const summary = screen.getByRole('region', { name: 'Time series' })
      .nextElementSibling as HTMLElement;
    expect(summary).toHaveTextContent('Minimum10');
    expect(summary).toHaveTextContent('Maximum40');
    expect(summary).toHaveTextContent(/Latest\s*12\s*at/);
    expect(summary).toHaveTextContent('Points3');
  });

  it('offers the same values as a table that opens on request', async () => {
    const { http, user } = await renderPage();
    answerNames(http);
    points(http).flush(series([10, null, 12]));
    anomalies(http).flush({ items: [] });

    const toggle = await screen.findByRole('button', { name: 'Show data table' });
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByRole('table')).not.toBeInTheDocument();

    await user.click(toggle);
    const table = screen.getByRole('table', { name: /latency\.p95 values by time/ });
    expect(within(table).getAllByRole('row')).toHaveLength(4);
    expect(within(table).getByText('no value')).toBeInTheDocument();
    expect(within(table).getByRole('columnheader', { name: 'Value (ms)' })).toBeInTheDocument();
    expect(screen.getByRole('region', { name: 'Values table' })).toHaveAttribute('tabindex', '0');
    expect(screen.getByRole('button', { name: 'Hide data table' })).toHaveAttribute(
      'aria-expanded',
      'true',
    );
  });

  it('says so when the range holds no values, and still lists nothing to acknowledge', async () => {
    const { http } = await renderPage();
    answerNames(http);
    points(http).flush(series([]));
    anomalies(http).flush({ items: [] });

    expect(
      await screen.findByRole('heading', { name: 'No values in this range' }),
    ).toBeInTheDocument();
    expect(screen.queryByTestId('chart')).not.toBeInTheDocument();
    expect(screen.getByText('No anomalies in this range.')).toBeInTheDocument();
  });

  it('announces a failure in plain words and loads again on request', async () => {
    const { http, user } = await renderPage();
    answerNames(http);
    points(http).flush(
      { type: 'urn:pulseboard:problem:internal-error', detail: 'SQL syntax error near' },
      { status: 500, statusText: 'x' },
    );
    anomalies(http).flush({ items: [] });

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Could not load the values');
    expect(alert).toHaveTextContent('Something went wrong on our side. Try again shortly.');
    expect(alert).not.toHaveTextContent('SQL');

    await user.click(screen.getByRole('button', { name: 'Try again' }));
    points(http).flush(series([1, 2]));
    anomalies(http).flush({ items: [] });
    expect(await screen.findByTestId('chart')).toBeInTheDocument();
  });

  it('tells the person when the metric does not exist', async () => {
    const { http } = await renderPage();
    answerNames(http);
    points(http).flush(
      { type: 'urn:pulseboard:problem:not-found' },
      { status: 404, statusText: 'x' },
    );
    anomalies(http).flush({ items: [] });

    expect(
      await screen.findByRole('heading', { name: 'This metric does not exist' }),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole('heading', { name: 'Anomalies in this range' }),
    ).not.toBeInTheDocument();
  });

  it('tells the person when the metric definition itself is not found', async () => {
    const { http } = await renderPage();
    http.expectOne('/api/v1/services').flush([]);
    http
      .expectOne(`/api/v1/metrics/${METRIC}`)
      .flush({ type: 'urn:pulseboard:problem:not-found' }, { status: 404, statusText: 'x' });
    points(http).flush(series([]));
    anomalies(http).flush({ items: [] });

    expect(
      await screen.findByRole('heading', { name: 'This metric does not exist' }),
    ).toBeInTheDocument();
  });

  it('asks for the metric by id and for no per-service metric lists', async () => {
    const { http } = await renderPage();
    answerNames(http);
    points(http).flush(series([1]));
    anomalies(http).flush({ items: [] });
    http.expectNone((req) => /\/services\/[^/]+\/metrics$/.test(req.url));
    expect(
      await screen.findByRole('heading', { level: 1, name: 'latency.p95' }),
    ).toBeInTheDocument();
  });

  it('reads another range when a preset is chosen, and says which one is active', async () => {
    const { http, user } = await renderPage();
    answerNames(http);
    points(http).flush(series([1]));
    anomalies(http).flush({ items: [] });
    await screen.findByTestId('chart');

    expect(screen.getByRole('button', { name: 'Last 24 hours' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    await user.click(screen.getByRole('button', { name: 'Last hour' }));

    const request = points(http);
    expect(
      Date.parse(request.request.params.get('to') as string) -
        Date.parse(request.request.params.get('from') as string),
    ).toBe(3_600_000);
    expect(request.request.params.get('step')).toBe('PT10S');
    request.flush(series([1]));
    anomalies(http).flush({ items: [] });
    expect(screen.getByRole('button', { name: 'Last hour' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    expect(screen.getByRole('button', { name: 'Last 24 hours' })).toHaveAttribute(
      'aria-pressed',
      'false',
    );
  });

  it('refuses a custom range that ends before it starts, without a request', async () => {
    const { http, user } = await renderPage();
    answerNames(http);
    points(http).flush(series([1]));
    anomalies(http).flush({ items: [] });
    await screen.findByTestId('chart');

    await user.click(screen.getByRole('button', { name: 'Custom range' }));
    const from = screen.getByLabelText('From');
    const to = screen.getByLabelText('To');
    await user.clear(from);
    await user.type(from, '2026-05-02T10:00');
    await user.clear(to);
    await user.type(to, '2026-05-01T10:00');
    await user.click(screen.getByRole('button', { name: 'Apply' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('The start must be before the end');
    http.expectNone((req) => req.url.includes('/points'));
  });

  it('reads a valid custom range with a step that fits', async () => {
    const { http, user } = await renderPage();
    answerNames(http);
    points(http).flush(series([1]));
    anomalies(http).flush({ items: [] });
    await screen.findByTestId('chart');

    await user.click(screen.getByRole('button', { name: 'Custom range' }));
    const from = screen.getByLabelText('From');
    const to = screen.getByLabelText('To');
    await user.clear(from);
    await user.type(from, '2026-04-01T00:00');
    await user.clear(to);
    await user.type(to, '2026-04-11T00:00');
    await user.click(screen.getByRole('button', { name: 'Apply' }));

    const request = points(http);
    expect(request.request.params.get('step')).toBe('PT3600S');
    request.flush(series([1]));
    anomalies(http).flush({ items: [] });
    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Custom range' })).toHaveAttribute(
        'aria-pressed',
        'true',
      ),
    );
  });

  describe('acknowledge', () => {
    it('lets an owner acknowledge an anomaly of the metric', async () => {
      const target = rawAnomaly({ metricId: METRIC, serviceId: SERVICE });
      const { http, user } = await renderPage('OWNER');
      answerNames(http);
      points(http).flush(series([1, 2]));
      anomalies(http).flush({ items: [target], totalItems: 1 });

      await user.click(await screen.findByRole('button', { name: /^Acknowledge/ }));
      http
        .expectOne(`/api/v1/anomalies/${target.id}/ack`)
        .flush({ ...target, acknowledgedAt: '2026-05-01T12:31:00Z', acknowledgedBy: 'user-1' });

      const list = screen.getByRole('list', { name: 'Anomalies of this metric' });
      expect(await within(list).findByText('Acknowledged')).toBeInTheDocument();
    });

    it('explains to a viewer why the button does nothing, and sends no request', async () => {
      const target = rawAnomaly({ metricId: METRIC, serviceId: SERVICE });
      const { http, user } = await renderPage('VIEWER');
      answerNames(http);
      points(http).flush(series([1, 2]));
      anomalies(http).flush({ items: [target], totalItems: 1 });

      const button = await screen.findByRole('button', { name: /^Acknowledge/ });
      expect(button).toHaveAttribute('aria-disabled', 'true');
      expect(button.getAttribute('aria-describedby')).toBe(
        screen.getByText('Only owners and admins can acknowledge anomalies.').id,
      );
      await user.click(button);
      http.expectNone((req) => req.url.endsWith('/ack'));
    });

    it('rolls back and reports a missing anomaly', async () => {
      const target = rawAnomaly({ metricId: METRIC, serviceId: SERVICE });
      const { http, user, notifier } = await renderPage('OWNER');
      answerNames(http);
      points(http).flush(series([1, 2]));
      anomalies(http).flush({ items: [target], totalItems: 1 });

      await user.click(await screen.findByRole('button', { name: /^Acknowledge/ }));
      http
        .expectOne(`/api/v1/anomalies/${target.id}/ack`)
        .flush({ type: 'urn:pulseboard:problem:not-found' }, { status: 404, statusText: 'x' });

      expect(await screen.findByRole('button', { name: /^Acknowledge/ })).toBeInTheDocument();
      expect(notifier.error).toHaveBeenCalledWith(
        'That anomaly no longer exists. Reload the list.',
      );
    });
  });
});
