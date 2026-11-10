import { ApiProblem } from '../../api/problem';
import {
  conditionSummary,
  describeRuleProblem,
  formatCooldown,
  targetDisplay,
} from './alert-models';
import { rawRule } from './alert-testing';

describe('conditionSummary', () => {
  it('writes a threshold with operator, value and unit', () => {
    expect(conditionSummary(rawRule({ operator: 'GT', threshold: 500, metricUnit: 'ms' }))).toBe(
      'value > 500 ms',
    );
    expect(conditionSummary(rawRule({ operator: 'LTE', threshold: 0.05, metricUnit: '' }))).toBe(
      'value <= 0.05',
    );
  });

  it('writes an anomaly rule with the minimum severity', () => {
    expect(
      conditionSummary(
        rawRule({
          condition: 'ANOMALY_SEVERITY',
          minSeverity: 'HIGH',
          operator: null,
          threshold: null,
        }),
      ),
    ).toBe('anomaly severity >= High');
  });
});

describe('formatCooldown', () => {
  it.each([
    [60, '1 min'],
    [300, '5 min'],
    [900, '15 min'],
    [3600, '1 h'],
    [5400, '1 h 30 min'],
    [21_600, '6 h'],
    [86_400, '24 h'],
    [90, '1 min 30 s'],
    [45, '45 s'],
  ])('%i seconds is %s', (seconds, text) => {
    expect(formatCooldown(seconds)).toBe(text);
  });
});

describe('targetDisplay', () => {
  it('shows only the host of a webhook, never its path or query', () => {
    const shown = targetDisplay(
      'WEBHOOK',
      'https://hooks.example.com/services/T0/B0/secret?token=abc',
    );
    expect(shown).toBe('hooks.example.com');
    expect(shown).not.toContain('secret');
    expect(shown).not.toContain('token');
  });

  it('does not echo a webhook value that is not a URL', () => {
    expect(targetDisplay('WEBHOOK', 'garbage with secret')).toBe('invalid address');
  });

  it('shows an e-mail label as it is', () => {
    expect(targetDisplay('EMAIL_LOG', 'oncall@example.com')).toBe('oncall@example.com');
  });
});

describe('describeRuleProblem', () => {
  const problem = (over: Partial<ApiProblem>): ApiProblem => ({
    status: 409,
    kind: 'unknown',
    title: 't',
    fieldErrors: [],
    ...over,
  });

  it('says clearly that the rule limit is reached', () => {
    expect(describeRuleProblem(problem({ kind: 'alert-rule-limit' }), 'save')).toMatch(
      /reached its limit of alert rules/,
    );
  });

  it('says the rule was changed elsewhere for a version conflict', () => {
    expect(describeRuleProblem(problem({ kind: 'version-conflict' }), 'toggle')).toMatch(
      /changed elsewhere/,
    );
  });

  it('tells a name conflict apart from a version conflict', () => {
    expect(describeRuleProblem(problem({ kind: 'conflict' }), 'save')).toMatch(
      /name already exists/,
    );
  });

  it('never shows the server detail', () => {
    const text = describeRuleProblem(
      problem({ status: 500, kind: 'internal-error', detail: 'java.lang.NullPointerException' }),
      'save',
    );
    expect(text).not.toMatch(/Null/);
  });
});
