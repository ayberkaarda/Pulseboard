import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { render, screen, waitFor, within } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';

import { errorInterceptor } from '../../core/http/interceptors';
import { SessionStore } from '../../core/session/session.store';
import { StubPage } from '../../core/testing/auth-testing';
import { ConfirmService } from '../../shared/confirm/confirm.service';
import { fakeSession } from '../anomalies/anomaly-testing';
import { AlertRule } from './alert-models';
import { AlertRulesPage } from './alert-rules.page';
import { rawRule, ruleProblem } from './alert-testing';

const LIST = '/api/v1/alert-rules';

async function renderPage(role: string | null = 'OWNER', rules?: AlertRule[]) {
  const confirm = jest.fn();
  const view = await render(AlertRulesPage, {
    providers: [
      provideHttpClient(withInterceptors([errorInterceptor])),
      provideHttpClientTesting(),
      provideRouter([
        { path: 'app/alerts/history', component: StubPage },
        { path: 'new', component: StubPage },
        { path: ':id', component: StubPage },
      ]),
      { provide: SessionStore, useValue: fakeSession(role) },
      { provide: ConfirmService, useValue: { confirm } },
    ],
  });
  const http = TestBed.inject(HttpTestingController);
  if (rules) {
    http.expectOne(LIST).flush(rules);
    await screen.findByRole('heading', { name: 'Rules' });
  }
  return { ...view, http, confirm, user: userEvent.setup() };
}

const SECRET_URL = 'https://hooks.example.com/services/T0/B0/very-secret?token=abc';

describe('AlertRulesPage', () => {
  it('shows a loading status, then the rules with their facts', async () => {
    const { http } = await renderPage();
    expect(screen.getByText('Loading alert rules')).toBeInTheDocument();

    http.expectOne(LIST).flush([
      rawRule({ name: 'Latency too high', target: SECRET_URL }),
      rawRule({
        name: 'Any serious anomaly',
        condition: 'ANOMALY_SEVERITY',
        minSeverity: 'HIGH',
        operator: null,
        threshold: null,
        cooldownSeconds: 3600,
        channel: 'EMAIL_LOG',
        target: 'oncall@example.com',
      }),
    ]);

    const items = await screen.findAllByRole('listitem');
    expect(items).toHaveLength(2);
    expect(within(items[0]).getByText('value > 500 ms')).toBeInTheDocument();
    expect(within(items[0]).getByText('15 min')).toBeInTheDocument();
    expect(within(items[0]).getByText('checkout / latency.p95')).toBeInTheDocument();
    expect(within(items[1]).getByText('anomaly severity >= High')).toBeInTheDocument();
    expect(within(items[1]).getByText('1 h')).toBeInTheDocument();
  });

  it('shows a webhook as its host only, never the path or query', async () => {
    await renderPage('OWNER', [rawRule({ target: SECRET_URL })]);
    expect(screen.getByText('Webhook, hooks.example.com')).toBeInTheDocument();
    expect(document.body.textContent).not.toContain('very-secret');
    expect(document.body.textContent).not.toContain('token=abc');
  });

  it('gives owners the switch, edit, delete and new rule controls', async () => {
    await renderPage('OWNER', [rawRule({ name: 'Latency too high' })]);
    expect(screen.getByRole('switch', { name: 'Enabled: Latency too high' })).toBeChecked();
    expect(screen.getByRole('link', { name: 'Edit Latency too high' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Delete Latency too high' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'New rule' })).toBeInTheDocument();
    expect(screen.queryByText(/only owners and admins can create/i)).not.toBeInTheDocument();
  });

  it('shows when a rule last triggered, with the exact time as title, or that it never did', async () => {
    const twoHoursAgo = new Date(Date.now() - 2 * 3_600_000).toISOString();
    await renderPage('OWNER', [
      rawRule({ name: 'Fired', lastTriggeredAt: twoHoursAgo }),
      rawRule({ name: 'Quiet', lastTriggeredAt: null }),
    ]);
    const [fired, quiet] = screen.getAllByRole('listitem');
    const when = within(fired).getByText('2 hours ago');
    expect(when).toHaveAttribute('title', expect.stringMatching(/^[A-Z][a-z]{2} \d{1,2}, \d{4} /));
    expect(within(quiet).getByText('Never triggered')).toBeInTheDocument();
    expect(within(quiet).getByText('Never triggered')).not.toHaveAttribute('title');
  });

  it('shows the masked address of a viewer as the server returned it, without a copy button', async () => {
    await renderPage('VIEWER', [rawRule({ target: 'https://hooks.example.com/***' })]);
    expect(screen.getByText('Webhook, https://hooks.example.com/***')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /copy/i })).not.toBeInTheDocument();
  });

  it('renders no controls for a viewer, and says why', async () => {
    await renderPage('VIEWER', [
      rawRule({ name: 'Latency too high', enabled: true }),
      rawRule({ name: 'Off rule', enabled: false }),
    ]);

    // Not rendered at all, rather than hidden or disabled.
    expect(screen.queryByRole('switch')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /delete/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /edit/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'New rule' })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Latency too high' })).not.toBeInTheDocument();

    expect(
      screen.getByText(/only owners and admins can create, change, switch or delete/i),
    ).toBeInTheDocument();
    expect(screen.getByText('Enabled')).toBeInTheDocument();
    expect(screen.getByText('Disabled')).toBeInTheDocument();
    // Reading the history stays possible.
    expect(screen.getAllByRole('link', { name: /History of/ })).toHaveLength(2);
  });

  it('shows an empty state with a call to action for an owner', async () => {
    await renderPage('OWNER', []);
    expect(screen.getByRole('heading', { name: 'No alert rules yet' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Create your first rule' })).toHaveAttribute(
      'href',
      '/new',
    );
  });

  it('shows the empty state to a viewer without a call to action', async () => {
    await renderPage('VIEWER', []);
    expect(screen.getByRole('heading', { name: 'No alert rules yet' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Create your first rule' })).not.toBeInTheDocument();
  });

  it('shows an error with a way to try again', async () => {
    const { http, user } = await renderPage();
    http.expectOne(LIST).flush({}, { status: 500, statusText: 'Server Error' });
    expect(await screen.findByRole('alert')).toHaveTextContent('Could not load alert rules');
    await user.click(screen.getByRole('button', { name: 'Try again' }));
    http.expectOne(LIST).flush([rawRule()]);
    expect(await screen.findByRole('list', { name: 'Alert rules' })).toBeInTheDocument();
  });

  describe('delete', () => {
    it('does nothing when the person cancels the confirmation', async () => {
      const rule = rawRule({ name: 'Keep me' });
      const { http, confirm, user } = await renderPage('OWNER', [rule]);
      confirm.mockResolvedValue(false);

      await user.click(screen.getByRole('button', { name: 'Delete Keep me' }));

      expect(confirm).toHaveBeenCalledWith(
        expect.objectContaining({ title: 'Delete this rule?', confirmLabel: 'Delete rule' }),
      );
      http.expectNone(`${LIST}/${rule.id}`);
      expect(screen.getByText('Keep me')).toBeInTheDocument();
    });

    it('deletes after the confirmation and moves focus to the list heading', async () => {
      const rule = rawRule({ name: 'Delete me' });
      const other = rawRule({ name: 'Stay' });
      const { http, confirm, user } = await renderPage('OWNER', [rule, other]);
      confirm.mockResolvedValue(true);

      await user.click(screen.getByRole('button', { name: 'Delete Delete me' }));

      const request = http.expectOne(`${LIST}/${rule.id}`);
      expect(request.request.method).toBe('DELETE');
      expect(screen.queryByText('Delete me')).not.toBeInTheDocument();
      request.flush(null, { status: 204, statusText: 'No Content' });
      await waitFor(() => expect(screen.getByRole('heading', { name: 'Rules' })).toHaveFocus());
      expect(screen.getByText('Stay')).toBeInTheDocument();
    });

    it('brings the rule back and says so when the server refuses', async () => {
      const rule = rawRule({ name: 'Delete me' });
      const { http, confirm, user } = await renderPage('OWNER', [rule]);
      confirm.mockResolvedValue(true);

      await user.click(screen.getByRole('button', { name: 'Delete Delete me' }));
      http.expectOne(`${LIST}/${rule.id}`).flush({}, { status: 500, statusText: 'Server Error' });

      expect(await screen.findByText('Delete me')).toBeInTheDocument();
      expect(screen.getByRole('alert')).toHaveTextContent(/went wrong on our side/);
    });
  });

  describe('switching', () => {
    it('shows the new state at once and sends the version it started from', async () => {
      const rule = rawRule({ name: 'Latency', enabled: true, version: 4 });
      const { http, user } = await renderPage('OWNER', [rule]);

      await user.click(screen.getByRole('switch', { name: 'Enabled: Latency' }));

      const request = http.expectOne(`${LIST}/${rule.id}`);
      expect(request.request.method).toBe('PATCH');
      expect(request.request.body).toEqual({ enabled: false, version: 4 });
      await waitFor(() =>
        expect(screen.getByRole('switch', { name: 'Enabled: Latency' })).not.toBeChecked(),
      );
      request.flush({ ...rule, enabled: false, version: 5 });
    });

    it('puts the switch back and offers a reload when the rule changed elsewhere', async () => {
      const rule = rawRule({ name: 'Latency', enabled: true, version: 1 });
      const { http, user } = await renderPage('OWNER', [rule]);

      await user.click(screen.getByRole('switch', { name: 'Enabled: Latency' }));
      http.expectOne(`${LIST}/${rule.id}`).flush(...ruleProblem('version-conflict', 409));

      const notice = await screen.findByRole('alert');
      expect(notice).toHaveTextContent('This rule was changed elsewhere');
      await waitFor(() =>
        expect(screen.getByRole('switch', { name: 'Enabled: Latency' })).toBeChecked(),
      );

      await user.click(within(notice).getByRole('button', { name: 'Reload rules' }));
      http.expectOne(LIST).flush([{ ...rule, enabled: false, version: 2 }]);
      await waitFor(() =>
        expect(screen.getByRole('switch', { name: 'Enabled: Latency' })).not.toBeChecked(),
      );
      expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    });
  });
});
