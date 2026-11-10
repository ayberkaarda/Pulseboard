import type { components } from '../../api/generated/schema';
import { ApiProblem, describeProblem } from '../../api/problem';
import { formatValue } from '../../shared/format/format-time';
import { SEVERITY_LABELS } from '../../shared/signal/signal.model';

type Schemas = components['schemas'];

export type AlertRule = Schemas['AlertRule'];
export type AlertEvent = Schemas['AlertEvent'];
export type AlertRuleRequest = Schemas['AlertRuleRequest'];
export type AlertRuleUpdateRequest = Schemas['AlertRuleUpdateRequest'];

export type AlertCondition = AlertRule['condition'];
export type AlertChannel = AlertRule['channel'];
export type AlertOperator = NonNullable<AlertRule['operator']>;
export type AlertSeverity = NonNullable<AlertRule['minSeverity']>;
export type DeliveryStatus = AlertEvent['deliveryStatus'];

/** Limits from the contract (AlertRuleRequest). */
export const COOLDOWN_MIN_SECONDS = 60;
export const COOLDOWN_MAX_SECONDS = 86_400;
export const NAME_MAX_LENGTH = 100;
export const TARGET_MAX_LENGTH = 2048;
/** The contract allows an e-mail label of up to 254 characters. */
export const EMAIL_LABEL_MAX_LENGTH = 254;
/** The history endpoint accepts 1 to 100 per page. */
export const HISTORY_PAGE_SIZE = 25;

export const OPERATOR_SYMBOLS: Readonly<Record<AlertOperator, string>> = {
  GT: '>',
  GTE: '>=',
  LT: '<',
  LTE: '<=',
};

export const OPERATOR_LABELS: Readonly<Record<AlertOperator, string>> = {
  GT: 'greater than',
  GTE: 'greater than or equal to',
  LT: 'less than',
  LTE: 'less than or equal to',
};

export const OPERATORS: readonly AlertOperator[] = ['GT', 'GTE', 'LT', 'LTE'];

export const CONDITION_LABELS: Readonly<Record<AlertCondition, string>> = {
  THRESHOLD: 'Threshold',
  ANOMALY_SEVERITY: 'Anomaly severity',
};

export const CHANNEL_LABELS: Readonly<Record<AlertChannel, string>> = {
  WEBHOOK: 'Webhook',
  EMAIL_LOG: 'E-mail log',
};

export const DELIVERY_LABELS: Readonly<Record<DeliveryStatus, string>> = {
  PENDING: 'Pending',
  SENT: 'Sent',
  FAILED: 'Failed',
};

export const DELIVERY_MEANINGS: Readonly<Record<DeliveryStatus, string>> = {
  PENDING: 'Waiting for delivery or for the next retry.',
  SENT: 'The receiver accepted the notification.',
  FAILED: 'Every attempt failed, or the receiver rejected it for good.',
};

export const SEVERITIES: readonly AlertSeverity[] = ['LOW', 'MEDIUM', 'HIGH'];

/** Cooldown presets in the editor, in seconds. */
export const COOLDOWN_PRESETS: readonly number[] = [300, 900, 3600, 21_600];

/** "value > 500 ms" or "anomaly severity >= High". */
export function conditionSummary(rule: AlertRule): string {
  if (rule.condition === 'ANOMALY_SEVERITY') {
    const severity = rule.minSeverity ? SEVERITY_LABELS[rule.minSeverity] : '?';
    return `anomaly severity >= ${severity}`;
  }
  const operator = rule.operator ? OPERATOR_SYMBOLS[rule.operator] : '?';
  const threshold = rule.threshold === null ? '?' : formatValue(rule.threshold);
  const unit = rule.metricUnit.trim() === '' ? '' : ` ${rule.metricUnit}`;
  return `value ${operator} ${threshold}${unit}`;
}

/** "5 min", "1 h", "1 h 30 min", "90 s". */
export function formatCooldown(seconds: number): string {
  if (seconds < 60) {
    return `${seconds} s`;
  }
  const hours = Math.floor(seconds / 3600);
  const minutes = Math.floor((seconds % 3600) / 60);
  const rest = seconds % 60;
  const parts = [
    hours > 0 ? `${hours} h` : '',
    minutes > 0 ? `${minutes} min` : '',
    rest > 0 ? `${rest} s` : '',
  ].filter((part) => part !== '');
  return parts.join(' ');
}

/**
 * What a list shows for a target: the host of a webhook URL, never the path or query (they may carry
 * a secret). A value that is not a URL is returned as is; it is an e-mail label.
 */
export function targetDisplay(channel: AlertChannel, target: string): string {
  if (channel !== 'WEBHOOK') {
    return target;
  }
  try {
    return new URL(target).hostname;
  } catch {
    return 'invalid address';
  }
}

export type RuleAction = 'save' | 'toggle' | 'delete' | 'load';

/** Text for a failed rule request, composed from the problem kind and status, never from server detail. */
export function describeRuleProblem(problem: ApiProblem, action: RuleAction): string {
  if (problem.kind === 'alert-rule-limit') {
    return 'Your workspace has reached its limit of alert rules. Delete a rule you no longer need, then try again.';
  }
  if (problem.kind === 'version-conflict') {
    return 'This rule was changed elsewhere. Reload the rules to see the current version.';
  }
  if (problem.status === 409 && problem.kind === 'conflict') {
    return 'A rule with this name already exists. Choose another name.';
  }
  if (problem.status === 404) {
    return action === 'load'
      ? 'That rule does not exist.'
      : 'That rule no longer exists. Reload the rules.';
  }
  if (problem.status === 403) {
    return 'Only owners and admins can change alert rules.';
  }
  return describeProblem(problem);
}
