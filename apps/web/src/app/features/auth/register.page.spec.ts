import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { render, screen, waitFor } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';

import { errorInterceptor } from '../../core/http/interceptors';
import { StubPage, tokenResponse } from '../../core/testing/auth-testing';
import { RegisterPage, slugify } from './register.page';

async function renderRegister() {
  const view = await render(RegisterPage, {
    providers: [
      provideHttpClient(withInterceptors([errorInterceptor])),
      provideHttpClientTesting(),
      provideRouter([
        { path: 'app/overview', component: StubPage },
        { path: 'login', component: StubPage },
      ]),
    ],
  });
  return {
    ...view,
    user: userEvent.setup(),
    http: TestBed.inject(HttpTestingController),
    router: TestBed.inject(Router),
  };
}

describe('slugify', () => {
  it.each([
    ['Acme Corp', 'acme-corp'],
    ['  Acme & Sons!  ', 'acme-sons'],
    ['Ünï', 'n'],
    ['', ''],
  ])('turns %p into %p', (name, slug) => {
    expect(slugify(name)).toBe(slug);
  });

  it('never exceeds 63 characters or ends with a hyphen', () => {
    const slug = slugify(`${'a'.repeat(62)} b`);
    expect(slug.length).toBeLessThanOrEqual(63);
    expect(slug.endsWith('-')).toBe(false);
  });
});

describe('RegisterPage', () => {
  beforeEach(() => sessionStorage.clear());

  it('suggests the workspace address from the name until the person edits it', async () => {
    const { user } = await renderRegister();
    const address = screen.getByLabelText(/Workspace address/);

    await user.type(screen.getByLabelText(/Workspace name/), 'Acme Corp');
    expect(address).toHaveValue('acme-corp');

    await user.clear(address);
    await user.type(address, 'my-own');
    await user.type(screen.getByLabelText(/Workspace name/), ' Two');
    expect(address).toHaveValue('my-own');
  });

  it('uses new-password and describes the contract limits on the fields', async () => {
    const { user } = await renderRegister();

    expect(screen.getByLabelText(/Password/)).toHaveAttribute('autocomplete', 'new-password');
    await user.type(screen.getByLabelText(/Password/), 'short');
    await user.click(screen.getByRole('button', { name: 'Create workspace' }));

    await waitFor(() =>
      expect(screen.getByLabelText(/Password/)).toHaveAccessibleDescription(
        'Use at least 12 characters.',
      ),
    );
    await user.clear(screen.getByLabelText(/Workspace address/));
    await user.type(screen.getByLabelText(/Workspace address/), '-bad-');
    await waitFor(() =>
      expect(screen.getByLabelText(/Workspace address/)).toHaveAccessibleDescription(
        /3 to 63 lower case letters/,
      ),
    );
  });

  it('registers with the contract body and lands on the overview', async () => {
    const { user, http, router } = await renderRegister();
    await user.type(screen.getByLabelText(/Workspace name/), 'Acme Corp');
    await user.type(screen.getByLabelText(/Email/), 'ada@acme.test');
    await user.type(screen.getByLabelText(/Password/), 'correct horse battery');

    await user.click(screen.getByRole('button', { name: 'Create workspace' }));

    const request = http.expectOne('/api/v1/auth/register');
    expect(request.request.body).toEqual({
      tenantName: 'Acme Corp',
      tenantSlug: 'acme-corp',
      email: 'ada@acme.test',
      password: 'correct horse battery',
    });
    request.flush(tokenResponse(1), { status: 201, statusText: 'Created' });
    await waitFor(() => expect(router.url).toBe('/app/overview'));
  });

  it('marks the address as taken when the server answers with a conflict', async () => {
    const { user, http } = await renderRegister();
    await user.type(screen.getByLabelText(/Workspace name/), 'Acme Corp');
    await user.type(screen.getByLabelText(/Email/), 'ada@acme.test');
    await user.type(screen.getByLabelText(/Password/), 'correct horse battery');

    await user.click(screen.getByRole('button', { name: 'Create workspace' }));
    http.expectOne('/api/v1/auth/register').flush(
      {
        type: 'urn:pulseboard:problem:conflict',
        detail: "Tenant slug 'acme-corp' is already taken",
      },
      { status: 409, statusText: 'Conflict' },
    );

    await waitFor(() =>
      expect(screen.getByLabelText(/Workspace address/)).toHaveAccessibleDescription(
        'This workspace address is taken.',
      ),
    );
    expect(screen.getByLabelText(/Workspace address/)).toHaveFocus();
    expect(screen.getByRole('button', { name: 'Create workspace' })).toBeEnabled();
  });

  it('maps server validation errors to their fields', async () => {
    const { user, http } = await renderRegister();
    await user.type(screen.getByLabelText(/Workspace name/), 'Acme Corp');
    await user.type(screen.getByLabelText(/Email/), 'ada@acme.test');
    await user.type(screen.getByLabelText(/Password/), 'correct horse battery');

    await user.click(screen.getByRole('button', { name: 'Create workspace' }));
    http.expectOne('/api/v1/auth/register').flush(
      {
        type: 'urn:pulseboard:problem:validation',
        errors: [{ field: 'password', message: 'is too common' }],
      },
      { status: 400, statusText: 'Bad Request' },
    );

    await waitFor(() =>
      expect(screen.getByLabelText(/Password/)).toHaveAccessibleDescription('is too common'),
    );
  });
});
