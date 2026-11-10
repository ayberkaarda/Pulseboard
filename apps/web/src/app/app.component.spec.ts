import { provideRouter } from '@angular/router';
import { render, screen } from '@testing-library/angular';

import { AppComponent } from './app.component';
import { THEME_STORAGE_KEY } from './core/theme/theme.service';

describe('AppComponent', () => {
  afterEach(() => {
    localStorage.clear();
    document.documentElement.removeAttribute('data-theme');
  });

  it('applies the stored theme on startup', async () => {
    localStorage.setItem(THEME_STORAGE_KEY, 'dark');

    await render(AppComponent, { providers: [provideRouter([])] });

    expect(document.documentElement).toHaveAttribute('data-theme', 'dark');
  });

  it('hosts the routed page', async () => {
    const { container } = await render(AppComponent, { providers: [provideRouter([])] });

    expect(container.querySelector('router-outlet')).not.toBeNull();
    expect(screen.queryByRole('navigation')).not.toBeInTheDocument();
  });
});
