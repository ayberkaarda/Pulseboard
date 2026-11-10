import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { render, screen } from '@testing-library/angular';

import { SessionStore } from '../../core/session/session.store';
import { ANNOUNCE_DELAY_MS, ConnectionStatusComponent } from './connection-status.component';
import { BATCH_MS, LiveAnnouncerComponent } from './live-announcer.component';
import { SourceLog, fakeSession } from './anomaly-testing';
import { AnomalyStreamService, EVENT_SOURCE_FACTORY } from './anomaly-stream.service';

describe('LiveAnnouncerComponent', () => {
  beforeEach(() => jest.useFakeTimers());
  afterEach(() => jest.useRealTimers());

  const region = () => screen.getByRole('status');

  it('says nothing until something arrived, then announces a burst once', async () => {
    const { rerender, fixture } = await render(LiveAnnouncerComponent, { inputs: { count: 0 } });
    expect(region()).toHaveTextContent('');

    for (const count of [1, 2, 3, 4, 5, 6, 7]) {
      await rerender({ inputs: { count } });
      await jest.advanceTimersByTimeAsync(300);
    }
    // Seven events within the batch window are still silent.
    expect(region()).toHaveTextContent('');

    await jest.advanceTimersByTimeAsync(BATCH_MS);
    fixture.detectChanges();
    expect(region()).toHaveTextContent('7 new anomalies');
  });

  it('reports only what is new since the last message', async () => {
    const { rerender, fixture } = await render(LiveAnnouncerComponent, { inputs: { count: 0 } });
    await rerender({ inputs: { count: 2 } });
    await jest.advanceTimersByTimeAsync(BATCH_MS);
    fixture.detectChanges();
    expect(region()).toHaveTextContent('2 new anomalies');

    await rerender({ inputs: { count: 3 } });
    await jest.advanceTimersByTimeAsync(BATCH_MS);
    fixture.detectChanges();
    expect(region()).toHaveTextContent('1 new anomaly');
  });

  it('starts counting again after the list was reloaded', async () => {
    const { rerender, fixture } = await render(LiveAnnouncerComponent, { inputs: { count: 0 } });
    await rerender({ inputs: { count: 4 } });
    await jest.advanceTimersByTimeAsync(BATCH_MS);
    fixture.detectChanges();
    await rerender({ inputs: { count: 0 } });
    await jest.advanceTimersByTimeAsync(10);
    await rerender({ inputs: { count: 1 } });
    await jest.advanceTimersByTimeAsync(BATCH_MS);
    fixture.detectChanges();
    expect(region()).toHaveTextContent('1 new anomaly');
  });
});

describe('ConnectionStatusComponent', () => {
  beforeEach(() => jest.useFakeTimers());
  afterEach(() => {
    TestBed.inject(AnomalyStreamService).shutdown();
    jest.useRealTimers();
  });

  async function renderStatus() {
    const log = new SourceLog();
    const view = await render(ConnectionStatusComponent, {
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: SessionStore, useValue: fakeSession('OWNER', { token: null }) },
        { provide: EVENT_SOURCE_FACTORY, useValue: log.factory },
      ],
    });
    return { ...view, stream: TestBed.inject(AnomalyStreamService) };
  }

  it('states the connection in words, immediately', async () => {
    const { stream, fixture } = await renderStatus();
    expect(screen.getByText('Live updates are off')).toBeInTheDocument();

    // No session: the stream gives up at once and the page says Offline.
    stream.retain();
    await jest.advanceTimersByTimeAsync(0);
    fixture.detectChanges();
    expect(screen.getByText('Offline')).toBeInTheDocument();
  });

  it('repeats a state in the live region only after it has held for a moment', async () => {
    const { stream, fixture } = await renderStatus();
    const live = () => screen.getByRole('status');
    stream.retain();
    await jest.advanceTimersByTimeAsync(0);
    fixture.detectChanges();

    await jest.advanceTimersByTimeAsync(ANNOUNCE_DELAY_MS - 100);
    fixture.detectChanges();
    expect(live()).toHaveTextContent('');
    await jest.advanceTimersByTimeAsync(100);
    fixture.detectChanges();
    expect(live()).toHaveTextContent('Live updates: offline');
  });
});
