import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { render, screen, within } from '@testing-library/angular';

import { errorInterceptor } from '../../core/http/interceptors';
import { StubPage } from '../../core/testing/auth-testing';
import { ServiceDetailPage } from './service-detail.page';

const ID = '11111111-1111-4111-8111-111111111111';
const SERVICE = {
  id: ID,
  name: 'checkout',
  environment: 'STAGING',
  createdAt: '2026-01-01T00:00:00Z',
};
const METRIC = {
  id: '33333333-3333-4333-8333-333333333333',
  serviceId: ID,
  name: 'http.latency',
  unit: 'ms',
  aggregation: 'P95',
  createdAt: '2026-01-01T00:00:00Z',
};

async function renderDetail(id = ID) {
  const view = await render(ServiceDetailPage, {
    inputs: { id },
    providers: [
      provideHttpClient(withInterceptors([errorInterceptor])),
      provideHttpClientTesting(),
      provideRouter([
        { path: 'app/metrics/:id', component: StubPage },
        { path: 'app/overview', component: StubPage },
      ]),
    ],
  });
  return { ...view, http: TestBed.inject(HttpTestingController) };
}

describe('ServiceDetailPage', () => {
  it('names the service and lists its metrics with links to the metric page', async () => {
    const { http } = await renderDetail();

    http.expectOne('/api/v1/services').flush([SERVICE]);
    http.expectOne(`/api/v1/services/${ID}/metrics`).flush([METRIC]);

    expect(await screen.findByRole('heading', { level: 1, name: 'checkout' })).toBeInTheDocument();
    expect(screen.getByText('STAGING environment')).toBeInTheDocument();
    const link = within(screen.getByRole('list', { name: 'Metrics' })).getByRole('link');
    expect(link).toHaveAttribute('href', `/app/metrics/${METRIC.id}?service=${SERVICE.id}`);
    expect(link).toHaveTextContent('http.latency');
    expect(link).toHaveTextContent('95th percentile, ms');
  });

  it('says so when the service is not in the workspace', async () => {
    const { http } = await renderDetail();

    http.expectOne('/api/v1/services').flush([]);
    http
      .expectOne(`/api/v1/services/${ID}/metrics`)
      .flush(null, { status: 404, statusText: 'Not Found' });

    expect(
      await screen.findByRole('heading', { name: 'This service does not exist' }),
    ).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Back to overview' })).toHaveAttribute(
      'href',
      '/app/overview',
    );
  });

  it('offers a retry when the metrics fail to load', async () => {
    const { http } = await renderDetail();
    http.expectOne('/api/v1/services').flush([SERVICE]);

    http.expectOne(`/api/v1/services/${ID}/metrics`).flush(null, { status: 503, statusText: 'x' });

    expect(await screen.findByRole('alert')).toHaveTextContent('temporarily unavailable');
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument();
  });
});
