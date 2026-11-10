import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { render, screen, within } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';

import { errorInterceptor } from '../../core/http/interceptors';
import { StubPage } from '../../core/testing/auth-testing';
import { OverviewPage } from './overview.page';

const SERVICE = {
  id: '11111111-1111-4111-8111-111111111111',
  name: 'checkout',
  environment: 'PROD',
  createdAt: '2026-01-01T00:00:00Z',
};

async function renderOverview() {
  const view = await render(OverviewPage, {
    providers: [
      provideHttpClient(withInterceptors([errorInterceptor])),
      provideHttpClientTesting(),
      provideRouter([{ path: 'app/services/:id', component: StubPage }]),
    ],
  });
  return { ...view, http: TestBed.inject(HttpTestingController) };
}

describe('OverviewPage', () => {
  it('shows a loading status, then each service with the no data health state', async () => {
    const { http } = await renderOverview();
    expect(screen.getByRole('status')).toHaveTextContent('Loading services');

    http.expectOne('/api/v1/services').flush([SERVICE]);

    const list = await screen.findByRole('list', { name: 'Services' });
    const link = within(list).getByRole('link', { name: /checkout/ });
    expect(link).toHaveAttribute('href', `/app/services/${SERVICE.id}`);
    expect(link).toHaveTextContent('PROD');
    expect(link).toHaveTextContent('No data');
  });

  it('explains that nothing is reporting yet', async () => {
    const { http } = await renderOverview();

    http.expectOne('/api/v1/services').flush([]);

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

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Something went wrong on our side. Try again shortly.');
    expect(alert).not.toHaveTextContent('NullPointer');

    await user.click(screen.getByRole('button', { name: 'Try again' }));
    http.expectOne('/api/v1/services').flush([SERVICE]);

    expect(await screen.findByRole('link', { name: /checkout/ })).toBeInTheDocument();
  });
});
