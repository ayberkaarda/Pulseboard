import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { render, screen, waitFor } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';

import { errorInterceptor } from '../../core/http/interceptors';
import { SessionStore } from '../../core/session/session.store';
import { StubPage, tokenResponse } from '../../core/testing/auth-testing';
import { LoginPage } from './login.page';

async function renderLogin(returnUrl?: string) {
  const view = await render(LoginPage, {
    providers: [
      provideHttpClient(withInterceptors([errorInterceptor])),
      provideHttpClientTesting(),
      provideRouter([
        { path: 'app/overview', component: StubPage },
        { path: 'app/anomalies', component: StubPage },
        { path: 'register', component: StubPage },
      ]),
      {
        provide: ActivatedRoute,
        useValue: {
          snapshot: {
            queryParamMap: convertToParamMap(returnUrl === undefined ? {} : { returnUrl }),
          },
        },
      },
    ],
  });
  return {
    ...view,
    user: userEvent.setup(),
    http: TestBed.inject(HttpTestingController),
    router: TestBed.inject(Router),
  };
}

async function fill(user: ReturnType<typeof userEvent.setup>, password = 'correct horse battery') {
  await user.type(screen.getByLabelText(/Workspace/), 'acme');
  await user.type(screen.getByLabelText(/Email/), 'ada@acme.test');
  await user.type(screen.getByLabelText(/Password/), password);
}

describe('LoginPage', () => {
  beforeEach(() => sessionStorage.clear());
  afterEach(() => jest.restoreAllMocks());

  it('declares the right autocomplete hints for password managers', async () => {
    await renderLogin();

    expect(screen.getByLabelText(/Email/)).toHaveAttribute('autocomplete', 'username');
    expect(screen.getByLabelText(/Password/)).toHaveAttribute('autocomplete', 'current-password');
    expect(screen.getByLabelText(/Password/)).toHaveAttribute('type', 'password');
  });

  it('explains every missing field next to it and sends nothing', async () => {
    const { user, http } = await renderLogin();

    await user.click(screen.getByRole('button', { name: 'Sign in' }));

    const workspace = screen.getByLabelText(/Workspace/);
    await waitFor(() => expect(workspace).toHaveAccessibleDescription(/Enter your workspace\./));
    expect(screen.getByLabelText(/Email/)).toHaveAccessibleDescription('Enter your email address.');
    expect(screen.getByLabelText(/Password/)).toHaveAccessibleDescription('Enter your password.');
    expect(workspace).toBeInvalid();
    expect(workspace).toHaveFocus();
    http.expectNone('/api/v1/auth/login');
  });

  it('rejects a malformed email before asking the server', async () => {
    const { user, http } = await renderLogin();

    await user.type(screen.getByLabelText(/Workspace/), 'acme');
    await user.type(screen.getByLabelText(/Email/), 'not-an-email');
    await user.type(screen.getByLabelText(/Password/), 'secret');
    await user.click(screen.getByRole('button', { name: 'Sign in' }));

    await waitFor(() =>
      expect(screen.getByLabelText(/Email/)).toHaveAccessibleDescription(
        'Enter a valid email address.',
      ),
    );
    http.expectNone('/api/v1/auth/login');
  });

  it('shows and hides the password with a labelled toggle', async () => {
    const { user } = await renderLogin();
    const field = screen.getByLabelText(/Password/);

    const show = screen.getByRole('button', { name: 'Show password' });
    expect(show).toHaveAttribute('aria-pressed', 'false');
    await user.click(show);

    expect(field).toHaveAttribute('type', 'text');
    const hide = screen.getByRole('button', { name: 'Hide password' });
    expect(hide).toHaveAttribute('aria-pressed', 'true');
    await user.click(hide);
    expect(field).toHaveAttribute('type', 'password');
  });

  it('submits the contract body, is disabled while waiting and lands on the overview', async () => {
    const { user, http, router } = await renderLogin();
    const log = jest.spyOn(console, 'log');
    const warn = jest.spyOn(console, 'warn');
    await user.type(screen.getByLabelText(/Workspace/), ' ACME ');
    await user.type(screen.getByLabelText(/Email/), ' ada@acme.test ');
    await user.type(screen.getByLabelText(/Password/), 'correct horse battery');

    await user.click(screen.getByRole('button', { name: 'Sign in' }));

    const request = http.expectOne('/api/v1/auth/login');
    expect(request.request.body).toEqual({
      tenantSlug: 'acme',
      email: 'ada@acme.test',
      password: 'correct horse battery',
    });
    const pending = screen.getByRole('button', { name: 'Signing in' });
    expect(pending).toBeDisabled();

    request.flush(tokenResponse(1));
    await waitFor(() => expect(router.url).toBe('/app/overview'));
    expect(TestBed.inject(SessionStore).isAuthenticated()).toBe(true);
    for (const call of [...log.mock.calls, ...warn.mock.calls]) {
      expect(JSON.stringify(call)).not.toContain('correct horse battery');
    }
  });

  it('sends one request however often the button is pressed', async () => {
    const { user, http } = await renderLogin();
    await fill(user);

    const button = screen.getByRole('button', { name: 'Sign in' });
    await user.dblClick(button);

    expect(http.match('/api/v1/auth/login')).toHaveLength(1);
  });

  it('returns to a safe return URL', async () => {
    const { user, http, router } = await renderLogin('/app/anomalies');
    await fill(user);

    await user.click(screen.getByRole('button', { name: 'Sign in' }));
    http.expectOne('/api/v1/auth/login').flush(tokenResponse(1));

    await waitFor(() => expect(router.url).toBe('/app/anomalies'));
  });

  it.each(['//evil.example', 'https://evil.example/app/overview', '/\\evil.example'])(
    'ignores the return URL %s',
    async (attempt) => {
      const { user, http, router } = await renderLogin(attempt);
      await fill(user);

      await user.click(screen.getByRole('button', { name: 'Sign in' }));
      http.expectOne('/api/v1/auth/login').flush(tokenResponse(1));

      await waitFor(() => expect(router.url).toBe('/app/overview'));
    },
  );

  it('says the credentials are wrong without revealing which part, and lets the person retry', async () => {
    const { user, http } = await renderLogin();
    await fill(user, 'wrong password!!');

    await user.click(screen.getByRole('button', { name: 'Sign in' }));
    http.expectOne('/api/v1/auth/login').flush(
      {
        type: 'urn:pulseboard:problem:unauthorized',
        title: 'Unauthorized',
        detail: 'Bad credentials for tenant acme',
      },
      { status: 401, statusText: 'Unauthorized' },
    );

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('The workspace, email or password is not right.');
    expect(alert).not.toHaveTextContent('Bad credentials');
    expect(screen.getByRole('button', { name: 'Sign in' })).toBeEnabled();
    expect(TestBed.inject(SessionStore).isAuthenticated()).toBe(false);
  });

  it('puts server validation messages on their fields', async () => {
    const { user, http } = await renderLogin();
    await fill(user);

    await user.click(screen.getByRole('button', { name: 'Sign in' }));
    http.expectOne('/api/v1/auth/login').flush(
      {
        type: 'urn:pulseboard:problem:validation',
        errors: [{ field: 'email', message: 'must be a well-formed email address' }],
      },
      { status: 400, statusText: 'Bad Request' },
    );

    await waitFor(() =>
      expect(screen.getByLabelText(/Email/)).toHaveAccessibleDescription(
        'must be a well-formed email address',
      ),
    );
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('tells the person how long to wait after rate limiting', async () => {
    const { user, http } = await renderLogin();
    await fill(user);

    await user.click(screen.getByRole('button', { name: 'Sign in' }));
    http
      .expectOne('/api/v1/auth/login')
      .flush(
        { type: 'urn:pulseboard:problem:rate-limited' },
        { status: 429, statusText: 'x', headers: { 'Retry-After': '30' } },
      );

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Too many requests. Try again in 30 seconds.',
    );
  });

  it('reports a network failure', async () => {
    const { user, http } = await renderLogin();
    await fill(user);

    await user.click(screen.getByRole('button', { name: 'Sign in' }));
    http.expectOne('/api/v1/auth/login').error(new ProgressEvent('error'));

    expect(await screen.findByRole('alert')).toHaveTextContent('Cannot reach Pulseboard');
  });
});
