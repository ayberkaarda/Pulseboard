import { Subscription } from 'rxjs';

import { ApiProblem } from '../../api/problem';
import {
  applyRuleProblem,
  buildRuleRequest,
  buildUpdateRequest,
  checkWebhookUrl,
  controlNameOf,
  createRuleForm,
  firstInvalidControl,
  ruleToFormValue,
  wireRuleForm,
} from './alert-rule-form';
import { METRIC_ID, rawRule } from './alert-testing';

describe('checkWebhookUrl', () => {
  it.each([
    'https://hooks.example.com/pulseboard',
    'https://hooks.example.com',
    '  https://hooks.example.com/a?b=c  ',
    'https://a.b.example.org:8443/x',
  ])('accepts %s', (url) => {
    expect(checkWebhookUrl(url)).toBeNull();
  });

  it.each([
    ['', 'Enter the address'],
    ['not a url', 'must not contain spaces'],
    ['hooks.example.com', 'full address'],
    ['http://hooks.example.com', 'must start with https://'],
    ['ftp://hooks.example.com', 'must start with https://'],
    ['https://user:pw@hooks.example.com', 'user name and password'],
    ['https://user@hooks.example.com', 'user name and password'],
    ['https://localhost/hook', 'private network'],
    ['https://app.localhost/hook', 'private network'],
    ['https://printer.local/hook', 'private network'],
    ['https://db.internal/hook', 'private network'],
    ['https://127.0.0.1/hook', 'not an IP address'],
    ['https://10.0.0.5/hook', 'not an IP address'],
    ['https://169.254.169.254/latest/meta-data', 'not an IP address'],
    ['https://2130706433/', 'not an IP address'],
    ['https://0x7f.1/', 'not an IP address'],
    ['https://[::1]/hook', 'not an IP address'],
    ['https://intranet/hook', 'full public host name'],
  ])('rejects %s with a hint', (url, hint) => {
    expect(checkWebhookUrl(url)).toContain(hint);
  });

  it('rejects an address longer than the contract allows', () => {
    expect(checkWebhookUrl(`https://hooks.example.com/${'a'.repeat(2048)}`)).toContain('at most');
  });
});

describe('rule form', () => {
  function wired() {
    const form = createRuleForm();
    const subscription: Subscription = wireRuleForm(form);
    return { form, subscription };
  }

  function fill(form: ReturnType<typeof createRuleForm>) {
    form.patchValue({
      name: 'Latency too high',
      serviceId: 's1',
      metricId: METRIC_ID,
      threshold: 500,
      channel: 'EMAIL_LOG',
      target: 'oncall@example.com',
    });
  }

  it('is invalid until the required fields are filled, then valid', () => {
    const { form } = wired();
    expect(form.valid).toBe(false);
    expect(firstInvalidControl(form)).toBe('name');
    fill(form);
    expect(form.valid).toBe(true);
    expect(firstInvalidControl(form)).toBeNull();
  });

  it('rejects a blank name and a name over 100 characters', () => {
    const { form } = wired();
    fill(form);
    form.controls.name.setValue('   ');
    expect(form.controls.name.hasError('required')).toBe(true);
    form.controls.name.setValue('x'.repeat(101));
    expect(form.controls.name.hasError('maxlength')).toBe(true);
    form.controls.name.setValue('x'.repeat(100));
    expect(form.controls.name.valid).toBe(true);
  });

  it('keeps the cooldown inside the contract limits, whole numbers only', () => {
    const { form } = wired();
    const cooldown = form.controls.cooldownSeconds;
    cooldown.setValue(59);
    expect(cooldown.hasError('min')).toBe(true);
    cooldown.setValue(60);
    expect(cooldown.valid).toBe(true);
    cooldown.setValue(86_400);
    expect(cooldown.valid).toBe(true);
    cooldown.setValue(86_401);
    expect(cooldown.hasError('max')).toBe(true);
    cooldown.setValue(90.5);
    expect(cooldown.hasError('whole')).toBe(true);
    cooldown.setValue(null);
    expect(cooldown.hasError('required')).toBe(true);
  });

  it('validates the target by channel: a URL for a webhook, any label for e-mail', () => {
    const { form } = wired();
    form.controls.target.setValue('oncall@example.com');
    expect(form.controls.target.hasError('webhook')).toBe(true);
    form.controls.channel.setValue('EMAIL_LOG');
    form.controls.target.setValue('oncall@example.com');
    expect(form.controls.target.valid).toBe(true);
    form.controls.target.setValue('x'.repeat(255));
    expect(form.controls.target.hasError('maxlength')).toBe(true);
    form.controls.target.setValue('  ');
    expect(form.controls.target.hasError('required')).toBe(true);
  });

  it('clears the target and re-validates when the channel changes', () => {
    const { form } = wired();
    form.controls.target.setValue('https://hooks.example.com/a');
    expect(form.controls.target.valid).toBe(true);
    form.controls.channel.setValue('EMAIL_LOG');
    expect(form.controls.target.value).toBe('');
    expect(form.controls.target.hasError('required')).toBe(true);
  });

  it('does not clear the target while a saved rule is being loaded into the form', () => {
    const form = createRuleForm();
    let loading = false;
    wireRuleForm(form, () => loading);
    loading = true;
    form.reset(ruleToFormValue(rawRule({ channel: 'EMAIL_LOG', target: 'oncall@example.com' })));
    loading = false;
    expect(form.controls.target.value).toBe('oncall@example.com');
  });

  it('shows the fields of one condition and removes those of the other', () => {
    const { form } = wired();
    expect(form.controls.operator.enabled).toBe(true);
    expect(form.controls.threshold.enabled).toBe(true);
    expect(form.controls.minSeverity.disabled).toBe(true);

    form.controls.condition.setValue('ANOMALY_SEVERITY');
    expect(form.controls.operator.disabled).toBe(true);
    expect(form.controls.threshold.disabled).toBe(true);
    expect(form.controls.minSeverity.enabled).toBe(true);
    // A disabled threshold must not block the form.
    fill(form);
    form.controls.condition.setValue('ANOMALY_SEVERITY');
    expect(form.valid).toBe(true);
  });

  it('puts the fields it hides back to their defaults when the condition changes', () => {
    const { form } = wired();
    form.controls.operator.setValue('LTE');
    form.controls.threshold.setValue(12);
    form.controls.condition.setValue('ANOMALY_SEVERITY');
    form.controls.minSeverity.setValue('HIGH');

    form.controls.condition.setValue('THRESHOLD');
    expect(form.controls.operator.value).toBe('GT');
    expect(form.controls.threshold.value).toBeNull();
    expect(form.controls.minSeverity.value).toBe('MEDIUM');
  });

  it('requires a finite threshold for THRESHOLD rules only', () => {
    const { form } = wired();
    fill(form);
    form.controls.threshold.setValue(null);
    expect(form.controls.threshold.hasError('required')).toBe(true);
    form.controls.threshold.setValue(Infinity);
    expect(form.controls.threshold.hasError('finite')).toBe(true);
    form.controls.threshold.setValue(0);
    expect(form.controls.threshold.valid).toBe(true);
    form.controls.threshold.setValue(-3.5);
    expect(form.controls.threshold.valid).toBe(true);
  });
});

describe('request building', () => {
  it('builds a THRESHOLD request without minSeverity and trims text', () => {
    const form = createRuleForm();
    form.patchValue({
      name: '  Latency too high ',
      metricId: METRIC_ID,
      operator: 'GTE',
      threshold: 500,
      cooldownSeconds: 300,
      channel: 'WEBHOOK',
      target: ' https://hooks.example.com/a ',
      enabled: false,
    });
    const request = buildRuleRequest(form.getRawValue());
    expect(request).toEqual({
      name: 'Latency too high',
      metricId: METRIC_ID,
      condition: 'THRESHOLD',
      operator: 'GTE',
      threshold: 500,
      cooldownSeconds: 300,
      channel: 'WEBHOOK',
      target: 'https://hooks.example.com/a',
      enabled: false,
    });
    expect('minSeverity' in request).toBe(false);
  });

  it('builds an ANOMALY_SEVERITY request without operator and threshold', () => {
    const form = createRuleForm();
    wireRuleForm(form);
    form.patchValue({ name: 'Any anomaly', metricId: METRIC_ID, channel: 'EMAIL_LOG' });
    form.controls.condition.setValue('ANOMALY_SEVERITY');
    form.patchValue({ minSeverity: 'HIGH', target: 'oncall@example.com' });
    const request = buildRuleRequest(form.getRawValue());
    expect(request).toMatchObject({ condition: 'ANOMALY_SEVERITY', minSeverity: 'HIGH' });
    expect('operator' in request).toBe(false);
    expect('threshold' in request).toBe(false);
  });

  it('carries the version the edit started from on an update', () => {
    const form = createRuleForm();
    form.patchValue({
      name: 'x',
      metricId: METRIC_ID,
      threshold: 1,
      target: 'https://a.example.com',
    });
    expect(buildUpdateRequest(form.getRawValue(), 7)).toMatchObject({ version: 7, name: 'x' });
  });

  it('round trips a saved rule through the form', () => {
    const rule = rawRule({
      condition: 'ANOMALY_SEVERITY',
      minSeverity: 'LOW',
      operator: null,
      threshold: null,
    });
    const form = createRuleForm();
    form.reset(ruleToFormValue(rule));
    form.controls.condition.updateValueAndValidity();
    const request = buildRuleRequest(form.getRawValue());
    expect(request).toMatchObject({
      name: rule.name,
      condition: 'ANOMALY_SEVERITY',
      minSeverity: 'LOW',
      cooldownSeconds: rule.cooldownSeconds,
      target: rule.target,
    });
  });
});

describe('server problems', () => {
  function problem(over: Partial<ApiProblem>): ApiProblem {
    return {
      status: 400,
      kind: 'validation',
      title: 'Validation failed',
      fieldErrors: [],
      ...over,
    };
  }

  it('maps JSON pointer and bean path field names to controls', () => {
    expect(controlNameOf('/target')).toBe('target');
    expect(controlNameOf('target')).toBe('target');
    expect(controlNameOf('/a/b')).toBe('a');
  });

  it('puts the server message on the control and returns no general message', () => {
    const form = createRuleForm();
    const general = applyRuleProblem(
      form,
      problem({
        fieldErrors: [{ field: '/target', message: 'host resolves to a private address' }],
      }),
    );
    expect(general).toBeNull();
    expect(form.controls.target.getError('server')).toBe('host resolves to a private address');
    expect(form.controls.target.touched).toBe(true);
  });

  it('clears the server message when the value is edited', () => {
    const form = createRuleForm();
    applyRuleProblem(form, problem({ fieldErrors: [{ field: 'name', message: 'taken' }] }));
    form.controls.name.setValue('another');
    expect(form.controls.name.hasError('server')).toBe(false);
  });

  it('marks the name for a name conflict', () => {
    const form = createRuleForm();
    const general = applyRuleProblem(form, problem({ status: 409, kind: 'conflict' }));
    expect(general).toBeNull();
    expect(form.controls.name.getError('server')).toMatch(/already exists/);
  });

  it('falls back to a status based message when no field can be named', () => {
    const form = createRuleForm();
    const general = applyRuleProblem(
      form,
      problem({ fieldErrors: [{ field: '/nothing', message: 'x' }] }),
    );
    expect(general).toBe('Some of the information is not valid.');
  });
});
