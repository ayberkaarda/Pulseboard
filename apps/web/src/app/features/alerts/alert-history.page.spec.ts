import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';

import { errorInterceptor } from '../../core/http/interceptors';
import { StubPage } from '../../core/testing/auth-testing';
import { SessionLifecycle } from '../../core/session/session-lifecycle';
import { SessionStore } from '../../core/session/session.store';
import { fakeSession } from '../anomalies/anomaly-testing';
import { AlertEvent } from './alert-models';
import { AUTO_REFRESH_MS, AlertHistoryPage } from './alert-history.page';
import { rawEvent, rawRule } from './alert-testing';

const EVENTS = '/api/v1/alert-events';
const RULES = '/api/v1/alert-rules';
const RULE = rawRule({ id: '33333333-3333-4333-8333-333333333333', name: 'Latency too high' });

const page = (items: AlertEvent[], over: object = {}) => ({
  items,
  page: 0,
  size: 25,
  totalItems: items.length,
  totalPages: 1,
  ...over,
});

async function renderPage(query: Record<string, string> = {}, userOptions = {}) {
  const view = await render(AlertHistoryPage, {
    providers: [
      provideHttpClient(withInterceptors([errorInterceptor])),
      provideHttpClientTesting(),
      provideRouter([{ path: 'login', component: StubPage }]),
      {
        provide: ActivatedRoute,
        useValue: { snapshot: { queryParamMap: convertToParamMap(query) } },
      },
      { provide: SessionStore, useValue: fakeSession('OWNER') },
    ],
  });
  const http = TestBed.inject(HttpTestingController);
  return { ...view, http, user: userEvent.setup(userOptions) };
}

const events = (http: HttpTestingController) => http.expectOne((req) => req.url === EVENTS);

/** Answers the two requests the page makes on opening. */
async function open(http: HttpTestingController, items: AlertEvent[], over: object = {}) {
  http.expectOne(RULES).flush([RULE]);
  events(http).flush(page(items, over));
  await screen.findByRole('list', { name: 'Alert events' }).catch(() => undefined);
}

describe('AlertHistoryPage', () => {
  it('shows a loading status, then rows with delivery state as glyph and word', async () => {
    const { http, container } = await renderPage();
    expect(screen.getByText('Loading alert history')).toBeInTheDocument();

    await open(http, [
      rawEvent({ deliveryStatus: 'SENT', attempts: 1 }),
      rawEvent({
        deliveryStatus: 'FAILED',
        attempts: 4,
        deliveredAt: null,
        lastError: 'HTTP 500',
      }),
      rawEvent({
        deliveryStatus: 'PENDING',
        attempts: 0,
        deliveredAt: null,
        nextAttemptAt: '2026-05-01T12:01:00Z',
      }),
    ]);

    const items = await screen.findAllByRole('listitem');
    expect(items).toHaveLength(3);
    expect(within(items[0]).getByText('Sent')).toBeInTheDocument();
    expect(within(items[0]).getByText('1 attempt')).toBeInTheDocument();
    expect(within(items[1]).getByText('Failed')).toBeInTheDocument();
    expect(within(items[1]).getByText('4 attempts')).toBeInTheDocument();
    expect(within(items[1]).getByText('Last error: HTTP 500')).toBeInTheDocument();
    expect(within(items[2]).getByText('Pending')).toBeInTheDocument();
    // One glyph per row, a different shape per state.
    const glyphs = container.querySelectorAll('app-delivery-glyph');
    expect(Array.from(glyphs).map((g) => g.getAttribute('data-state'))).toEqual([
      'SENT',
      'FAILED',
      'PENDING',
    ]);
  });

  it('gives the exact trigger time in the title of the relative time', async () => {
    const { http } = await renderPage();
    await open(http, [rawEvent({ triggeredAt: '2026-05-01T12:00:00Z' })]);
    const time = document.querySelector('time');
    expect(time).toHaveAttribute('datetime', '2026-05-01T12:00:00Z');
    expect(time?.getAttribute('title')).toMatch(/2026/);
    expect(time?.textContent).toMatch(/ago/);
  });

  it('shows an empty state, and a different one when filters hide everything', async () => {
    const { http, user } = await renderPage();
    await open(http, []);
    expect(
      await screen.findByRole('heading', { name: 'No alerts have fired yet' }),
    ).toBeInTheDocument();

    await user.selectOptions(screen.getByLabelText('Status'), 'FAILED');
    events(http).flush(page([]));
    expect(
      await screen.findByRole('heading', { name: 'No alerts match these filters' }),
    ).toBeInTheDocument();
  });

  it('shows an error with a way to try again', async () => {
    const { http, user } = await renderPage();
    http.expectOne(RULES).flush([]);
    events(http).flush({}, { status: 500, statusText: 'Server Error' });
    expect(await screen.findByRole('alert')).toHaveTextContent('Could not load the alert history');
    await user.click(screen.getByRole('button', { name: 'Try again' }));
    events(http).flush(page([rawEvent()]));
    expect(await screen.findByRole('list', { name: 'Alert events' })).toBeInTheDocument();
  });

  describe('filters and paging', () => {
    it('filters by status on the server and returns to the first page', async () => {
      const { http, user } = await renderPage();
      await open(http, [rawEvent()], { totalItems: 60, totalPages: 3 });

      await user.click(screen.getByRole('button', { name: 'Next' }));
      expect(events(http).request.params.get('page')).toBe('1');
      // (left unanswered on purpose: the next change replaces it)
      await user.selectOptions(screen.getByLabelText('Status'), 'FAILED');
      const request = http
        .match((req) => req.url === EVENTS)
        .filter((req) => !req.cancelled)
        .pop();
      expect(request?.request.params.get('status')).toBe('FAILED');
      expect(request?.request.params.get('page')).toBe('0');
    });

    it('filters by rule and by date range', async () => {
      const { http, user } = await renderPage();
      await open(http, [rawEvent()]);

      await user.selectOptions(screen.getByLabelText('Rule'), RULE.id);
      expect(events(http).request.params.get('ruleId')).toBe(RULE.id);

      await user.type(screen.getByLabelText('From'), '2026-05-01');
      const withFrom = events(http);
      expect(withFrom.request.params.get('from')).toBe(new Date(2026, 4, 1).toISOString());
      expect(withFrom.request.params.get('ruleId')).toBe(RULE.id);
    });

    it('does not ask the server for an end date before the start date, and says so', async () => {
      const { http, user } = await renderPage();
      await open(http, [rawEvent()]);
      await user.type(screen.getByLabelText('From'), '2026-05-03');
      events(http).flush(page([rawEvent()]));
      await user.type(screen.getByLabelText('To'), '2026-05-01');

      http.expectNone((req) => req.url === EVENTS);
      expect(await screen.findByRole('alert')).toHaveTextContent(/must not be before/);
      expect(screen.getByLabelText('To')).toHaveAccessibleDescription(/must not be before/);
    });

    it('pages with Previous and Next and states where it is', async () => {
      const { http, user } = await renderPage();
      await open(http, [rawEvent()], { totalItems: 60, totalPages: 3 });

      expect(screen.getByText('Page 1 of 3, 60 in total')).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Previous' })).toBeDisabled();

      await user.click(screen.getByRole('button', { name: 'Next' }));
      const request = events(http);
      expect(request.request.params.get('page')).toBe('1');
      request.flush(page([rawEvent()], { page: 1, totalItems: 60, totalPages: 3 }));

      expect(await screen.findByText('Page 2 of 3, 60 in total')).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Previous' })).toBeEnabled();
    });

    it('opens filtered by rule when the address names one', async () => {
      const { http } = await renderPage({ ruleId: RULE.id });
      http.expectOne(RULES).flush([RULE]);
      expect(events(http).request.params.get('ruleId')).toBe(RULE.id);
      await waitFor(() => expect(screen.getByLabelText('Rule')).toHaveValue(RULE.id));
    });

    it('ignores a rule id in the address that is not an id', async () => {
      const { http } = await renderPage({ ruleId: "x' or '1'='1" });
      http.expectOne(RULES).flush([RULE]);
      expect(events(http).request.params.has('ruleId')).toBe(false);
    });
  });

  describe('event details', () => {
    const XSS_PAYLOAD = {
      title: '<img src=x onerror="window.__pwned = 1">',
      nested: { html: '<script>window.__pwned = 2</script>' },
      long: 'x'.repeat(500),
    };
    const XSS_ERROR = '<img src=x onerror="window.__pwned = 3"><b>bold</b>';

    afterEach(() => {
      delete (window as unknown as Record<string, unknown>)['__pwned'];
    });

    async function openDetails() {
      const env = await renderPage();
      await open(env.http, [
        rawEvent({
          deliveryStatus: 'FAILED',
          deliveredAt: null,
          lastError: XSS_ERROR,
          payload: XSS_PAYLOAD,
          ruleName: '<i>Latency</i>',
        }),
      ]);
      await env.user.click(await screen.findByRole('button', { name: /^Details of/ }));
      return { ...env, dialog: await screen.findByRole('dialog') };
    }

    it('shows the payload as pretty printed text in a scrollable region', async () => {
      const { dialog } = await openDetails();
      const region = within(dialog).getByRole('region', { name: 'Payload' });
      expect(region.tagName).toBe('PRE');
      expect(region).toHaveAttribute('tabindex', '0');
      expect(region.textContent).toBe(JSON.stringify(XSS_PAYLOAD, null, 2));
    });

    it('keeps markup in the payload, the error and the rule name inert', async () => {
      const { dialog } = await openDetails();

      // Nothing was parsed as HTML anywhere in the page.
      expect(document.querySelector('img')).toBeNull();
      expect(document.querySelector('script')).toBeNull();
      expect(document.querySelector('b')).toBeNull();
      expect(document.querySelector('i')).toBeNull();
      expect((window as unknown as Record<string, unknown>)['__pwned']).toBeUndefined();

      // The text itself is there, as text.
      expect(within(dialog).getByText(XSS_ERROR)).toBeInTheDocument();
      expect(screen.getAllByText(/<i>Latency<\/i>/).length).toBeGreaterThan(0);
    });

    it('copies the payload text', async () => {
      const { dialog, user } = await openDetails();
      // After user-event is set up, which installs its own clipboard.
      const writeText = jest.fn().mockResolvedValue(undefined);
      Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });

      await user.click(within(dialog).getByRole('button', { name: 'Copy payload' }));

      expect(writeText).toHaveBeenCalledWith(JSON.stringify(XSS_PAYLOAD, null, 2));
      expect(await within(dialog).findByText('Payload copied.')).toBeInTheDocument();
    });

    it('says so when copying is not possible', async () => {
      const { dialog, user } = await openDetails();
      Object.defineProperty(navigator, 'clipboard', {
        value: { writeText: jest.fn().mockRejectedValue(new Error('denied')) },
        configurable: true,
      });
      await user.click(within(dialog).getByRole('button', { name: 'Copy payload' }));
      expect(await within(dialog).findByText(/Copying is not available/)).toBeInTheDocument();
    });

    it('closes the dialog and drops its payload when the session is cleared, also on the way to the login', async () => {
      const { dialog } = await openDetails();
      expect(within(dialog).getByText(/long/)).toBeInTheDocument();

      TestBed.inject(SessionLifecycle).clear();
      await TestBed.inject(Router).navigateByUrl('/login');

      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
      expect(document.querySelector('.cdk-overlay-container')?.textContent ?? '').not.toContain(
        'xxxxxxxx',
      );
      expect(screen.queryByText(/Alert event/)).not.toBeInTheDocument();
    });

    it('does not open a dialog whose lazy import finished after the session was cleared', async () => {
      const env = await renderPage();
      await open(env.http, [rawEvent({ payload: XSS_PAYLOAD })]);
      const button = await screen.findByRole('button', { name: /^Details of/ });

      fireEvent.click(button);
      TestBed.inject(SessionLifecycle).clear();
      await new Promise((resolve) => setTimeout(resolve, 100));

      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    });

    it('returns focus to the row button when the dialog closes', async () => {
      const { dialog, user } = await openDetails();
      const opener = screen.getByRole('button', { name: /^Details of/ });
      await user.click(within(dialog).getByRole('button', { name: 'Close' }));
      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
      expect(opener).toHaveFocus();
    });
  });
});

describe('AlertHistoryPage auto refresh', () => {
  let visibility = 'visible';

  beforeEach(() => {
    jest.useFakeTimers();
    visibility = 'visible';
    Object.defineProperty(document, 'visibilityState', {
      configurable: true,
      get: () => visibility,
    });
  });

  afterEach(() => {
    jest.useRealTimers();
    delete (document as unknown as Record<string, unknown>)['visibilityState'];
  });

  const pending = () => rawEvent({ deliveryStatus: 'PENDING', deliveredAt: null, attempts: 0 });

  async function renderPending(items: AlertEvent[]) {
    const env = await renderPage({}, { advanceTimers: jest.advanceTimersByTime });
    await open(env.http, items);
    return env;
  }

  it('reads again every 30 seconds while something is pending and the tab is visible', async () => {
    const { http } = await renderPending([pending()]);

    jest.advanceTimersByTime(AUTO_REFRESH_MS / 2);
    http.expectNone((req) => req.url === EVENTS);
    jest.advanceTimersByTime(AUTO_REFRESH_MS / 2 + 1000);
    events(http).flush(page([pending()]));

    jest.advanceTimersByTime(AUTO_REFRESH_MS);
    events(http).flush(page([rawEvent({ deliveryStatus: 'SENT' })]));
  });

  it('stops by itself once nothing is pending', async () => {
    const { http, fixture } = await renderPending([pending()]);
    jest.advanceTimersByTime(AUTO_REFRESH_MS);
    events(http).flush(page([rawEvent({ deliveryStatus: 'SENT' })]));
    fixture.detectChanges();
    await Promise.resolve();

    jest.advanceTimersByTime(AUTO_REFRESH_MS * 3);
    http.expectNone((req) => req.url === EVENTS);
  });

  it('does not poll when nothing is pending', async () => {
    const { http } = await renderPending([rawEvent({ deliveryStatus: 'FAILED' })]);
    jest.advanceTimersByTime(AUTO_REFRESH_MS * 3);
    http.expectNone((req) => req.url === EVENTS);
  });

  it('does not poll while the tab is hidden, and resumes when it is visible again', async () => {
    const { http } = await renderPending([pending()]);
    visibility = 'hidden';
    jest.advanceTimersByTime(AUTO_REFRESH_MS * 2);
    http.expectNone((req) => req.url === EVENTS);

    visibility = 'visible';
    jest.advanceTimersByTime(AUTO_REFRESH_MS);
    events(http).flush(page([pending()]));
  });

  it('stops polling when the page is left', async () => {
    const { http, fixture } = await renderPending([pending()]);
    fixture.destroy();
    jest.advanceTimersByTime(AUTO_REFRESH_MS * 3);
    http.expectNone((req) => req.url === EVENTS);
  });
});
