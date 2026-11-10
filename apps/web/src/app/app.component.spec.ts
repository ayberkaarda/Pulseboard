import { provideRouter } from '@angular/router';
import { render, screen, waitFor, within } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';

import { AppComponent, WIDE_LAYOUT_QUERY } from './app.component';
import { THEME_STORAGE_KEY } from './core/theme/theme.service';

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

async function renderShell(wide = true) {
  stubViewport(wide);
  return render(AppComponent, { providers: [provideRouter([])] });
}

describe('AppComponent shell', () => {
  afterEach(() => {
    localStorage.clear();
    document.documentElement.removeAttribute('data-theme');
  });

  it('shows the wordmark, primary navigation and the overview page', async () => {
    await renderShell();

    expect(screen.getAllByText('pulseboard')[0]).toBeVisible();
    const nav = screen.getByRole('navigation', { name: 'Primary' });
    const items = within(nav).getAllByRole('button');
    expect(items.map((item) => item.textContent?.trim())).toEqual([
      'Overview',
      'Services',
      'Anomalies',
      'Alert rules',
    ]);
    expect(screen.getByRole('heading', { level: 1, name: 'Overview' })).toBeInTheDocument();
    expect(within(nav).getByRole('button', { name: 'Overview' })).toHaveAttribute(
      'aria-current',
      'page',
    );
    expect(screen.getByRole('heading', { name: 'No services reporting yet' })).toBeInTheDocument();
  });

  it('switches the page when a navigation item is chosen', async () => {
    const user = userEvent.setup();
    await renderShell();
    const nav = screen.getByRole('navigation', { name: 'Primary' });

    await user.click(within(nav).getByRole('button', { name: 'Anomalies' }));

    expect(screen.getByRole('heading', { level: 1, name: 'Anomalies' })).toBeInTheDocument();
    expect(within(nav).getByRole('button', { name: 'Anomalies' })).toHaveAttribute(
      'aria-current',
      'page',
    );
    expect(within(nav).getByRole('button', { name: 'Overview' })).not.toHaveAttribute(
      'aria-current',
    );
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

  it('restores a stored theme on startup', async () => {
    localStorage.setItem(THEME_STORAGE_KEY, 'light');
    await renderShell();

    expect(document.documentElement).toHaveAttribute('data-theme', 'light');
    expect(screen.getByRole('radio', { name: 'Light theme' })).toBeChecked();
  });

  it('opens and closes the signal key dialog', async () => {
    const user = userEvent.setup();
    await renderShell();

    await user.click(screen.getByRole('button', { name: 'Read the signal key' }));

    const dialog = await screen.findByRole('dialog', { name: 'Signal key' });
    for (const label of ['Healthy', 'Degraded', 'Down', 'No data', 'Low', 'Medium', 'High']) {
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
