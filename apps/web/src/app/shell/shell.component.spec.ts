import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { render, screen, waitFor, within } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';

import { THEME_STORAGE_KEY } from '../core/theme/theme.service';
import { SessionStore } from '../core/session/session.store';
import { ShellComponent, WIDE_LAYOUT_QUERY } from './shell.component';

function stubViewport(wide: boolean): void {
  jest.spyOn(window, 'matchMedia').mockImplementation((query: string): MediaQueryList => {
    const list: Pick<
      MediaQueryList,
      | 'matches'
      | 'media'
      | 'addListener'
      | 'removeListener'
      | 'addEventListener'
      | 'removeEventListener'
    > = {
      matches: wide && query === WIDE_LAYOUT_QUERY,
      media: query,
      addListener: () => undefined,
      removeListener: () => undefined,
      addEventListener: () => undefined,
      removeEventListener: () => undefined,
    };
    return list as MediaQueryList;
  });
}

@Component({ selector: 'app-test-page', template: '<h1>{{ name }}</h1>' })
class OverviewStub {
  protected readonly name = 'Overview page';
}

@Component({ selector: 'app-test-anomalies', template: '<h1>Anomalies page</h1>' })
class AnomaliesStub {}

@Component({ selector: 'app-test-login', template: '<h1>Login page</h1>' })
class LoginStub {}

async function renderShell(wide = true) {
  stubViewport(wide);
  const view = await render(ShellComponent, {
    providers: [provideHttpClient(), provideHttpClientTesting()],
    routes: [
      { path: 'app/overview', component: OverviewStub },
      { path: 'app/services/:id', component: OverviewStub },
      { path: 'app/anomalies', component: AnomaliesStub },
      { path: 'login', component: LoginStub },
    ],
  });
  await view.navigate('/app/overview');
  return view;
}

describe('ShellComponent', () => {
  afterEach(() => {
    localStorage.clear();
    document.documentElement.removeAttribute('data-theme');
  });

  it('shows the wordmark and primary navigation as links to real routes', async () => {
    await renderShell();

    expect(screen.getAllByText('pulseboard')[0]).toBeVisible();
    const nav = screen.getByRole('navigation', { name: 'Primary' });
    const items = within(nav).getAllByRole('link');
    expect(items.map((item) => item.textContent?.trim())).toEqual(['Overview', 'Anomalies']);
    expect(items.map((item) => item.getAttribute('href'))).toEqual([
      '/app/overview',
      '/app/anomalies',
    ]);
    expect(screen.getByRole('heading', { level: 1, name: 'Overview page' })).toBeInTheDocument();
    expect(within(nav).getByRole('link', { name: 'Overview' })).toHaveAttribute(
      'aria-current',
      'page',
    );
  });

  it('switches the page through the router and moves focus to the content', async () => {
    const user = userEvent.setup();
    await renderShell();
    const nav = screen.getByRole('navigation', { name: 'Primary' });

    await user.click(within(nav).getByRole('link', { name: 'Anomalies' }));

    expect(await screen.findByRole('heading', { level: 1, name: 'Anomalies page' })).toBeVisible();
    expect(within(nav).getByRole('link', { name: 'Anomalies' })).toHaveAttribute(
      'aria-current',
      'page',
    );
    expect(within(nav).getByRole('link', { name: 'Overview' })).not.toHaveAttribute('aria-current');
    expect(screen.getByRole('main')).toHaveFocus();
  });

  it('keeps Overview marked for the pages that belong to it', async () => {
    const { navigate } = await renderShell();
    await navigate('/app/services/abc');

    const nav = screen.getByRole('navigation', { name: 'Primary' });
    expect(within(nav).getByRole('link', { name: 'Overview' })).toHaveAttribute(
      'aria-current',
      'page',
    );
  });

  it('signs out: clears the session and returns to the login page', async () => {
    const user = userEvent.setup();
    await renderShell();
    const session = TestBed.inject(SessionStore);
    const logout = jest.spyOn(session, 'logout');

    await user.click(screen.getByRole('button', { name: 'Sign out' }));

    expect(logout).toHaveBeenCalledTimes(1);
    expect(TestBed.inject(Router).url).toBe('/login');
  });

  it('applies and remembers an explicit theme, and returns to the system theme', async () => {
    const user = userEvent.setup();
    await renderShell();
    const root = document.documentElement;

    expect(root).not.toHaveAttribute('data-theme');

    await user.click(screen.getByRole('radio', { name: 'Dark theme' }));
    expect(root).toHaveAttribute('data-theme', 'dark');
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe('dark');

    await user.click(screen.getByRole('radio', { name: 'Match system theme' }));
    expect(root).not.toHaveAttribute('data-theme');
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBeNull();
  });

  it('opens and closes the signal key dialog', async () => {
    const user = userEvent.setup();
    await renderShell();

    await user.click(screen.getByRole('button', { name: 'Signal key' }));

    const dialog = await screen.findByRole('dialog', { name: 'Signal key' });
    for (const label of [
      'Healthy',
      'Degraded',
      'Down',
      'Stale',
      'No data',
      'Low',
      'Medium',
      'High',
    ]) {
      expect(within(dialog).getByText(label)).toBeInTheDocument();
    }

    await user.click(within(dialog).getByRole('button', { name: 'Close signal key' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  });

  it('collapses navigation into a drawer on narrow screens', async () => {
    const user = userEvent.setup();
    await renderShell(false);

    const toggle = screen.getByRole('button', { name: 'Open navigation' });
    expect(toggle).toHaveAttribute('aria-expanded', 'false');

    await user.click(toggle);

    expect(toggle).toHaveAttribute('aria-expanded', 'true');
  });
});
