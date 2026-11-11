import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { render, screen, waitFor, within } from '@testing-library/angular';
import userEvent from '@testing-library/user-event';

import { errorInterceptor } from '../../core/http/interceptors';
import { AlertRulesStore } from './alert-rules.store';
import { Notifier } from '../../core/notify/notifier.service';
import { SessionStore } from '../../core/session/session.store';
import { StubPage } from '../../core/testing/auth-testing';
import { fakeSession } from '../anomalies/anomaly-testing';
import { AlertRuleEditorPage } from './alert-rule-editor.page';
import { METRIC_ID, SERVICE_ID, rawRule, ruleProblem } from './alert-testing';

const LIST = '/api/v1/alert-rules';
const SERVICE = {
  id: SERVICE_ID,
  name: 'checkout',
  environment: 'PROD',
  createdAt: '2026-01-01T00:00:00Z',
};
const METRIC = {
  id: METRIC_ID,
  serviceId: SERVICE_ID,
  name: 'latency.p95',
  unit: 'ms',
  aggregation: 'P95',
  createdAt: '2026-01-01T00:00:00Z',
};

async function renderEditor(options: { role?: string; id?: string } = {}) {
  const notifier = { error: jest.fn(), info: jest.fn() };
  const view = await render(AlertRuleEditorPage, {
    providers: [
      provideHttpClient(withInterceptors([errorInterceptor])),
      provideHttpClientTesting(),
      provideRouter([{ path: 'app/alerts/rules', component: StubPage }]),
      {
        provide: ActivatedRoute,
        useValue: {
          snapshot: { paramMap: convertToParamMap(options.id ? { id: options.id } : {}) },
        },
      },
      { provide: SessionStore, useValue: fakeSession(options.role ?? 'OWNER') },
      { provide: Notifier, useValue: notifier },
    ],
  });
  const http = TestBed.inject(HttpTestingController);
  return { ...view, http, notifier, user: userEvent.setup(), page: view.fixture.componentInstance };
}

/** Answers the services request and opens the metric list of the service. */
async function openCatalog(http: HttpTestingController) {
  http.expectOne('/api/v1/services').flush([SERVICE]);
}

async function chooseMetric(user: ReturnType<typeof userEvent.setup>, http: HttpTestingController) {
  await user.selectOptions(await screen.findByLabelText(/^Service/), SERVICE_ID);
  http.expectOne(`/api/v1/services/${SERVICE_ID}/metrics`).flush([METRIC]);
  await waitFor(() =>
    expect(screen.getByRole('option', { name: 'latency.p95 (ms)' })).toBeInTheDocument(),
  );
  await user.selectOptions(screen.getByLabelText(/^Metric/), METRIC_ID);
}

async function fillThresholdRule(
  user: ReturnType<typeof userEvent.setup>,
  http: HttpTestingController,
) {
  await openCatalog(http);
  await user.type(screen.getByLabelText(/^Rule name/), 'Latency too high');
  await chooseMetric(user, http);
  await user.type(screen.getByLabelText(/^Threshold/), '500');
  await user.type(screen.getByLabelText(/^Webhook URL/), 'https://hooks.example.com/pulseboard');
}

describe('AlertRuleEditorPage creating a rule', () => {
  it('shows the metric unit next to the threshold and sends a THRESHOLD request', async () => {
    const { http, user } = await renderEditor();
    await fillThresholdRule(user, http);

    expect(screen.getByLabelText(/^Threshold/)).toHaveAccessibleDescription(/In ms/);

    await user.click(screen.getByRole('button', { name: '1 h' }));
    await user.click(screen.getByRole('button', { name: 'Save rule' }));

    const request = http.expectOne(LIST);
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({
      name: 'Latency too high',
      metricId: METRIC_ID,
      condition: 'THRESHOLD',
      operator: 'GT',
      threshold: 500,
      cooldownSeconds: 3600,
      channel: 'WEBHOOK',
      target: 'https://hooks.example.com/pulseboard',
      enabled: true,
    });
  });

  it('goes back to the list and reports success after a save', async () => {
    const { http, user, notifier } = await renderEditor();
    await fillThresholdRule(user, http);
    await user.click(screen.getByRole('button', { name: 'Save rule' }));
    http.expectOne(LIST).flush(rawRule(), { status: 201, statusText: 'Created' });

    await waitFor(() => expect(TestBed.inject(Router).url).toBe('/app/alerts/rules'));
    expect(notifier.info).toHaveBeenCalledWith('Rule saved');
  });

  it('disables the button while the request is pending and ignores a second submit', async () => {
    const { http, user, page } = await renderEditor();
    await fillThresholdRule(user, http);

    await user.click(screen.getByRole('button', { name: 'Save rule' }));
    expect(screen.getByRole('button', { name: 'Saving' })).toBeDisabled();
    // A second submit that gets past the disabled button (Enter in a field) is ignored too.
    await (page as unknown as { submit(): Promise<void> }).submit();

    http.expectOne(LIST).flush(rawRule(), { status: 201, statusText: 'Created' });
    http.expectNone(LIST);
  });

  it('removes the threshold fields and shows the severity group for an anomaly rule, announcing it', async () => {
    const { http, user } = await renderEditor();
    await openCatalog(http);

    expect(screen.getByLabelText(/^Threshold/)).toBeInTheDocument();
    expect(screen.queryByRole('group', { name: 'Minimum severity' })).not.toBeInTheDocument();

    const anomaly = screen.getByRole('radio', { name: 'An anomaly reaches a severity' });
    await user.click(anomaly);

    expect(screen.queryByLabelText(/^Threshold/)).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/^Operator/)).not.toBeInTheDocument();
    const severities = screen.getByRole('group', { name: 'Minimum severity' });
    // Shape and word, never colour alone: each option has its label next to a glyph.
    expect(within(severities).getByRole('radio', { name: 'Medium' })).toBeChecked();
    expect(within(severities).getByRole('radio', { name: 'High' })).toBeInTheDocument();
    expect(severities.querySelectorAll('app-severity-glyph')).toHaveLength(3);
    // Focus stays on the control the person used.
    expect(anomaly).toHaveFocus();
    expect(screen.getByRole('status')).toHaveTextContent(/Severity field shown/);

    await user.click(screen.getByRole('radio', { name: 'The value crosses a threshold' }));
    expect(screen.getByLabelText(/^Threshold/)).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent(/Threshold fields shown/);
  });

  it('sends an ANOMALY_SEVERITY request without operator and threshold', async () => {
    const { http, user } = await renderEditor();
    await openCatalog(http);
    await user.type(screen.getByLabelText(/^Rule name/), 'Serious anomalies');
    await chooseMetric(user, http);
    await user.click(screen.getByRole('radio', { name: 'An anomaly reaches a severity' }));
    await user.click(screen.getByRole('radio', { name: 'High' }));
    await user.click(screen.getByRole('radio', { name: 'E-mail log' }));
    await user.type(screen.getByLabelText(/^Recipient label/), 'oncall@example.com');
    await user.click(screen.getByRole('button', { name: 'Save rule' }));

    const body = http.expectOne(LIST).request.body;
    expect(body).toEqual({
      name: 'Serious anomalies',
      metricId: METRIC_ID,
      condition: 'ANOMALY_SEVERITY',
      minSeverity: 'HIGH',
      cooldownSeconds: 900,
      channel: 'EMAIL_LOG',
      target: 'oncall@example.com',
      enabled: true,
    });
  });

  it('changes the meaning of the target when the channel changes and clears the old value', async () => {
    const { http, user } = await renderEditor();
    await openCatalog(http);
    await user.type(screen.getByLabelText(/^Webhook URL/), 'https://hooks.example.com/a');

    await user.click(screen.getByRole('radio', { name: 'E-mail log' }));

    const target = screen.getByLabelText(/^Recipient label/);
    expect(target).toHaveValue('');
    expect(target).toHaveAccessibleDescription(/no e-mail is sent/);
    expect(screen.getByRole('status')).toHaveTextContent(/previous target was cleared/);
  });

  it('blocks a private webhook target in the browser and explains it by the field', async () => {
    const { http, user } = await renderEditor();
    await fillThresholdRule(user, http);
    const target = screen.getByLabelText(/^Webhook URL/);
    await user.clear(target);
    await user.type(target, 'http://localhost:8080/hook');
    await user.click(screen.getByRole('button', { name: 'Save rule' }));

    http.expectNone(LIST);
    await waitFor(() => expect(target).toHaveAccessibleDescription(/must start with https/));
    expect(target).toHaveAttribute('aria-invalid', 'true');
    expect(target).toHaveFocus();

    await user.clear(target);
    await user.type(target, 'https://169.254.169.254/latest');
    await waitFor(() => expect(target).toHaveAccessibleDescription(/not an IP address/));
  });

  it('puts server field messages on the control, tied by aria-describedby, and focuses it', async () => {
    const { http, user } = await renderEditor();
    await fillThresholdRule(user, http);
    await user.click(screen.getByRole('button', { name: 'Save rule' }));

    http
      .expectOne(LIST)
      .flush(
        ...ruleProblem('validation', 400, [
          { field: '/target', message: 'host resolves to a private address' },
        ]),
      );

    const target = screen.getByLabelText(/^Webhook URL/);
    await waitFor(() =>
      expect(target).toHaveAccessibleDescription('host resolves to a private address'),
    );
    expect(target).toHaveFocus();
    expect(screen.getByRole('alert')).toHaveTextContent(/did not accept some fields/);
    // The button is usable again.
    expect(screen.getByRole('button', { name: 'Save rule' })).toBeEnabled();
  });

  it('says clearly that the rule limit is reached', async () => {
    const { http, user } = await renderEditor();
    await fillThresholdRule(user, http);
    await user.click(screen.getByRole('button', { name: 'Save rule' }));
    http.expectOne(LIST).flush(...ruleProblem('alert-rule-limit', 409));

    expect(await screen.findByRole('alert')).toHaveTextContent(/reached its limit of alert rules/);
    expect(TestBed.inject(Router).url).not.toBe('/app/alerts/rules');
  });

  it('marks the name when it is taken', async () => {
    const { http, user } = await renderEditor();
    await fillThresholdRule(user, http);
    await user.click(screen.getByRole('button', { name: 'Save rule' }));
    http.expectOne(LIST).flush(...ruleProblem('conflict', 409));

    await waitFor(() =>
      expect(screen.getByLabelText(/^Rule name/)).toHaveAccessibleDescription(/already exists/),
    );
    expect(screen.getByLabelText(/^Rule name/)).toHaveFocus();
  });

  it('does not write the webhook address to storage or the console', async () => {
    const setItem = jest.spyOn(Storage.prototype, 'setItem');
    const log = jest.spyOn(console, 'log').mockImplementation(() => undefined);
    const warn = jest.spyOn(console, 'warn').mockImplementation(() => undefined);
    const error = jest.spyOn(console, 'error').mockImplementation(() => undefined);

    const { http, user } = await renderEditor();
    await fillThresholdRule(user, http);
    await user.click(screen.getByRole('button', { name: 'Save rule' }));
    http
      .expectOne(LIST)
      .flush(...ruleProblem('validation', 400, [{ field: '/target', message: 'not allowed' }]));
    await screen.findByRole('alert');

    const seen = JSON.stringify([
      setItem.mock.calls,
      log.mock.calls,
      warn.mock.calls,
      error.mock.calls,
      Object.entries(localStorage),
      Object.entries(sessionStorage),
    ]);
    expect(seen).not.toContain('hooks.example.com');
  });

  it('asks nothing when nothing was changed, and flags unsaved changes after typing', async () => {
    const { http, user, page } = await renderEditor();
    await openCatalog(http);
    expect(page.hasUnsavedChanges()).toBe(false);
    await user.type(screen.getByLabelText(/^Rule name/), 'x');
    expect(page.hasUnsavedChanges()).toBe(true);
  });
});

describe('AlertRuleEditorPage editing a rule', () => {
  async function renderEdit(rule = rawRule({ version: 3 })) {
    const env = await renderEditor({ id: rule.id });
    env.http.expectOne(LIST).flush([rule]);
    env.http.expectOne('/api/v1/services').flush([SERVICE]);
    (await waitFor(() => env.http.expectOne(`/api/v1/services/${SERVICE_ID}/metrics`))).flush([
      METRIC,
    ]);
    await waitFor(() => expect(screen.getByLabelText(/^Rule name/)).toHaveValue(rule.name));
    return { ...env, rule };
  }

  it('loads the newest version of the rule, finds its service and starts clean', async () => {
    const { page } = await renderEdit(rawRule({ name: 'Existing rule', threshold: 750 }));
    expect(screen.getByLabelText(/^Threshold/)).toHaveValue(750);
    await waitFor(() => expect(screen.getByLabelText(/^Service/)).toHaveValue(SERVICE_ID));
    expect(screen.getByLabelText(/^Metric/)).toHaveValue(METRIC_ID);
    expect(screen.getByLabelText(/^Webhook URL/)).toHaveValue(
      'https://hooks.example.com/pulseboard/secret-path?token=abc',
    );
    expect(page.hasUnsavedChanges()).toBe(false);
  });

  it('sends a PUT with the version it read', async () => {
    const { http, user, rule } = await renderEdit();
    const name = screen.getByLabelText(/^Rule name/);
    await user.clear(name);
    await user.type(name, 'Renamed');
    await user.click(screen.getByRole('button', { name: 'Save rule' }));

    const request = http.expectOne(`${LIST}/${rule.id}`);
    expect(request.request.method).toBe('PUT');
    expect(request.request.body).toMatchObject({ name: 'Renamed', version: 3, threshold: 500 });
    request.flush({ ...rule, name: 'Renamed', version: 4 });
    await waitFor(() => expect(TestBed.inject(Router).url).toBe('/app/alerts/rules'));
  });

  it('sends the version the form started from, so a toggle that finished meanwhile is a conflict, not a silent revert', async () => {
    const { http, user, rule } = await renderEdit();
    const store = TestBed.inject(AlertRulesStore);

    // A toggle answered after the editor was filled moves the store to version 4.
    const toggled = store.toggle(rule, false);
    const patch = http.expectOne(`${LIST}/${rule.id}`);
    expect(patch.request.method).toBe('PATCH');
    patch.flush({ ...rule, enabled: false, version: 4 });
    await toggled;

    expect(await screen.findByText('This rule changed since you opened it.')).toBeInTheDocument();

    const name = screen.getByLabelText(/^Rule name/);
    await user.clear(name);
    await user.type(name, 'Renamed');
    await user.click(screen.getByRole('button', { name: 'Save rule' }));

    const put = http.expectOne(`${LIST}/${rule.id}`);
    expect(put.request.method).toBe('PUT');
    expect(put.request.body).toMatchObject({ version: 3, enabled: true });
    put.flush(...ruleProblem('version-conflict', 409));
    expect(await screen.findByText(/This rule was changed elsewhere/)).toBeInTheDocument();
  });

  it('offers a reload when the rule changes in the store while the editor is open', async () => {
    const { http, user, rule } = await renderEdit();
    expect(screen.queryByText('This rule changed since you opened it.')).not.toBeInTheDocument();
    const store = TestBed.inject(AlertRulesStore);

    const toggled = store.toggle(rule, false);
    http.expectOne(`${LIST}/${rule.id}`).flush({ ...rule, enabled: false, version: 4 });
    await toggled;
    await user.click(await screen.findByRole('button', { name: 'Reload the rule' }));

    http.expectOne(LIST).flush([{ ...rule, enabled: false, version: 4 }]);
    await waitFor(() =>
      expect(screen.queryByText('This rule changed since you opened it.')).not.toBeInTheDocument(),
    );
    await user.click(screen.getByRole('button', { name: 'Save rule' }));
    expect(http.expectOne(`${LIST}/${rule.id}`).request.body).toMatchObject({ version: 4 });
  });

  it('is read only while a save is pending, so no edit can be lost', async () => {
    const { http, user, rule } = await renderEdit();
    await user.click(screen.getByRole('button', { name: 'Save rule' }));

    expect(screen.getByLabelText(/^Rule name/)).toBeDisabled();
    expect(screen.getByLabelText(/^Threshold/)).toBeDisabled();
    expect(document.querySelector('form')).toHaveAttribute('aria-busy', 'true');

    const put = http.expectOne(`${LIST}/${rule.id}`);
    put.flush(...ruleProblem('version-conflict', 409));
    await waitFor(() => expect(screen.getByLabelText(/^Rule name/)).toBeEnabled());
  });

  it('never overwrites on a version conflict: it says so and offers the latest version', async () => {
    const { http, user, rule, page } = await renderEdit();
    const name = screen.getByLabelText(/^Rule name/);
    await user.clear(name);
    await user.type(name, 'My edit');
    await user.click(screen.getByRole('button', { name: 'Save rule' }));
    http.expectOne(`${LIST}/${rule.id}`).flush(...ruleProblem('version-conflict', 409));

    const notice = await screen.findByText(/changed elsewhere/);
    expect(notice).toBeInTheDocument();
    // The edits are still there: nothing was thrown away silently.
    expect(name).toHaveValue('My edit');
    expect(page.hasUnsavedChanges()).toBe(true);
    http.expectNone(`${LIST}/${rule.id}`);

    await user.click(screen.getByRole('button', { name: 'Load latest version' }));
    http.expectOne(LIST).flush([{ ...rule, name: 'Theirs', version: 5 }]);
    await waitFor(() => expect(screen.getByLabelText(/^Rule name/)).toHaveValue('Theirs'));
    expect(page.hasUnsavedChanges()).toBe(false);

    // The next save carries the new version.
    await user.click(screen.getByRole('button', { name: 'Save rule' }));
    expect(http.expectOne(`${LIST}/${rule.id}`).request.body.version).toBe(5);
  });

  it('can keep editing after a conflict', async () => {
    const { http, user, rule } = await renderEdit();
    await user.type(screen.getByLabelText(/^Rule name/), '!');
    await user.click(screen.getByRole('button', { name: 'Save rule' }));
    http.expectOne(`${LIST}/${rule.id}`).flush(...ruleProblem('version-conflict', 409));
    await screen.findByText(/changed elsewhere/);

    await user.click(screen.getByRole('button', { name: 'Keep editing' }));
    expect(screen.queryByText(/changed elsewhere/)).not.toBeInTheDocument();
  });

  it('says so when the rule was deleted elsewhere', async () => {
    const { http, user, rule } = await renderEdit();
    await user.type(screen.getByLabelText(/^Rule name/), '!');
    await user.click(screen.getByRole('button', { name: 'Save rule' }));
    http.expectOne(`${LIST}/${rule.id}`).flush(...ruleProblem('not-found', 404));
    expect(await screen.findByText(/deleted elsewhere/)).toBeInTheDocument();
  });

  it('shows not found for a rule that is not in the list', async () => {
    const { http } = await renderEditor({ id: 'ffffffff-ffff-4fff-8fff-ffffffffffff' });
    http.expectOne(LIST).flush([rawRule()]);
    http.expectOne('/api/v1/services').flush([SERVICE]);
    expect(await screen.findByText('This rule does not exist')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Save rule' })).not.toBeInTheDocument();
  });
});

describe('AlertRuleEditorPage leaving the page', () => {
  const leave = () => {
    const event = new Event('beforeunload', { cancelable: true });
    window.dispatchEvent(event);
    return event.defaultPrevented;
  };

  it('asks the browser to confirm only while the form has unsaved changes', async () => {
    const { http, user } = await renderEditor();
    await openCatalog(http);
    expect(leave()).toBe(false);
    await user.type(screen.getByLabelText(/^Rule name/), 'x');
    expect(leave()).toBe(true);
  });

  it('stays quiet once the form is saved', async () => {
    const { http, user } = await renderEditor();
    await fillThresholdRule(user, http);
    expect(leave()).toBe(true);
    await user.click(screen.getByRole('button', { name: 'Save rule' }));
    http.expectOne(LIST).flush(rawRule(), { status: 201, statusText: 'Created' });
    await waitFor(() => expect(TestBed.inject(Router).url).toBe('/app/alerts/rules'));
    expect(leave()).toBe(false);
  });

  it('removes its listener when the page is destroyed', async () => {
    const add = jest.spyOn(window, 'addEventListener');
    const remove = jest.spyOn(window, 'removeEventListener');
    const { http, user, fixture } = await renderEditor();
    await openCatalog(http);
    await user.type(screen.getByLabelText(/^Rule name/), 'x');
    const added = add.mock.calls.filter(([type]) => type === 'beforeunload');
    expect(added).toHaveLength(1);

    fixture.destroy();
    expect(remove.mock.calls.filter(([type]) => type === 'beforeunload')).toEqual(added);
    expect(leave()).toBe(false);
  });
});

describe('AlertRuleEditorPage as a viewer', () => {
  it('renders no form at all, only the explanation', async () => {
    await renderEditor({ role: 'VIEWER' });
    expect(
      screen.getByText(/Only owners and admins can create or change alert rules/),
    ).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Save rule' })).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/^Rule name/)).not.toBeInTheDocument();
  });
});
