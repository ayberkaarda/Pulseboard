import { TestBed } from '@angular/core/testing';
import { screen, waitFor } from '@testing-library/angular';

import { Notifier } from './notifier.service';

function liveRegion(message: string): HTMLElement | null {
  const visible = screen.queryAllByText(message)[0];
  return visible?.closest('.mat-mdc-snack-bar-container')?.querySelector('[aria-live]') ?? null;
}

describe('Notifier', () => {
  it('announces an error assertively and offers a way to dismiss it', async () => {
    await TestBed.inject(Notifier).error('Cannot reach Pulseboard.');

    await screen.findByText('Cannot reach Pulseboard.');
    expect(liveRegion('Cannot reach Pulseboard.')).toHaveAttribute('aria-live', 'assertive');
    await waitFor(() =>
      expect(liveRegion('Cannot reach Pulseboard.')).toHaveTextContent('Cannot reach Pulseboard.'),
    );
    expect(screen.getByRole('button', { name: 'Dismiss' })).toBeInTheDocument();
  });

  it('announces information politely', async () => {
    await TestBed.inject(Notifier).info('Signed out.');

    await screen.findByText('Signed out.');
    expect(liveRegion('Signed out.')).toHaveAttribute('aria-live', 'polite');
  });

  it('shows the same message once while it is still on screen', async () => {
    const notifier = TestBed.inject(Notifier);

    await Promise.all([notifier.error('Try again shortly.'), notifier.error('Try again shortly.')]);
    await notifier.error('Try again shortly.');

    await screen.findByText('Try again shortly.');
    expect(screen.getAllByText('Try again shortly.')).toHaveLength(1);
  });
});
