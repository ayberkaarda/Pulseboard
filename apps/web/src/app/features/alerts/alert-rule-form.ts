import {
  AbstractControl,
  FormControl,
  FormGroup,
  ValidationErrors,
  Validators,
} from '@angular/forms';
import { Subscription } from 'rxjs';

import { ApiProblem, describeProblem } from '../../api/problem';
import {
  AlertChannel,
  AlertCondition,
  AlertOperator,
  AlertRule,
  AlertRuleRequest,
  AlertRuleUpdateRequest,
  AlertSeverity,
  COOLDOWN_MAX_SECONDS,
  COOLDOWN_MIN_SECONDS,
  EMAIL_LABEL_MAX_LENGTH,
  NAME_MAX_LENGTH,
  TARGET_MAX_LENGTH,
} from './alert-models';

/**
 * The browser only helps: it says early what the server would refuse. The server decides.
 * Returns the message to show, or null when the address looks acceptable.
 */
export function checkWebhookUrl(value: string): string | null {
  const text = value.trim();
  if (text === '') {
    return 'Enter the address that should receive the notification.';
  }
  if (text.length > TARGET_MAX_LENGTH) {
    return `Use an address of at most ${TARGET_MAX_LENGTH} characters.`;
  }
  if (/\s/.test(text)) {
    return 'The address must not contain spaces.';
  }
  let url: URL;
  try {
    url = new URL(text);
  } catch {
    return 'Enter a full address, for example https://hooks.example.com/pulseboard.';
  }
  if (url.protocol !== 'https:') {
    return 'The address must start with https://.';
  }
  if (url.username !== '' || url.password !== '') {
    return 'Remove the user name and password from the address.';
  }
  const host = url.hostname.toLowerCase().replace(/\.$/, '');
  // The URL parser has already turned decimal, octal and hexadecimal spellings into dotted form.
  if (host.startsWith('[') || /^\d+(\.\d+){3}$/.test(host)) {
    return 'Use a public host name, not an IP address.';
  }
  if (isInternalName(host)) {
    return 'Addresses on this machine or on a private network are not allowed.';
  }
  if (!host.includes('.')) {
    return 'Use a full public host name, such as hooks.example.com.';
  }
  return null;
}

const INTERNAL_SUFFIXES: readonly string[] = [
  '.localhost',
  '.local',
  '.internal',
  '.intranet',
  '.lan',
  '.home',
  '.corp',
  '.home.arpa',
];

function isInternalName(host: string): boolean {
  return host === 'localhost' || INTERNAL_SUFFIXES.some((suffix) => host.endsWith(suffix));
}

/** Validation of the target field, which depends on the channel chosen in the same form. */
export function targetValidator(control: AbstractControl): ValidationErrors | null {
  const channel = control.parent?.get('channel')?.value as AlertChannel | undefined;
  const value = typeof control.value === 'string' ? control.value : '';
  if (channel === 'EMAIL_LOG') {
    if (value.trim() === '') {
      return { required: true };
    }
    return value.length > EMAIL_LABEL_MAX_LENGTH
      ? { maxlength: { requiredLength: EMAIL_LABEL_MAX_LENGTH } }
      : null;
  }
  const message = checkWebhookUrl(value);
  return message === null ? null : { webhook: message };
}

/** Rejects names that are only spaces; the server trims too. */
export function notBlank(control: AbstractControl): ValidationErrors | null {
  return typeof control.value === 'string' && control.value.trim() === ''
    ? { required: true }
    : null;
}

function wholeNumber(control: AbstractControl): ValidationErrors | null {
  const value: unknown = control.value;
  return typeof value === 'number' && !Number.isInteger(value) ? { whole: true } : null;
}

function finiteNumber(control: AbstractControl): ValidationErrors | null {
  const value: unknown = control.value;
  return typeof value === 'number' && !Number.isFinite(value) ? { finite: true } : null;
}

export interface RuleFormControls {
  name: FormControl<string>;
  /** Only narrows the metric list; never sent. */
  serviceId: FormControl<string>;
  metricId: FormControl<string>;
  condition: FormControl<AlertCondition>;
  operator: FormControl<AlertOperator>;
  threshold: FormControl<number | null>;
  minSeverity: FormControl<AlertSeverity>;
  cooldownSeconds: FormControl<number | null>;
  channel: FormControl<AlertChannel>;
  target: FormControl<string>;
  enabled: FormControl<boolean>;
}

export type RuleForm = FormGroup<RuleFormControls>;

export const DEFAULT_COOLDOWN_SECONDS = 900;

export function createRuleForm(): RuleForm {
  const form = new FormGroup<RuleFormControls>({
    name: new FormControl('', {
      nonNullable: true,
      validators: [Validators.required, notBlank, Validators.maxLength(NAME_MAX_LENGTH)],
    }),
    serviceId: new FormControl('', { nonNullable: true, validators: [Validators.required] }),
    metricId: new FormControl('', { nonNullable: true, validators: [Validators.required] }),
    condition: new FormControl<AlertCondition>('THRESHOLD', { nonNullable: true }),
    operator: new FormControl<AlertOperator>('GT', { nonNullable: true }),
    threshold: new FormControl<number | null>(null, {
      validators: [Validators.required, finiteNumber],
    }),
    minSeverity: new FormControl<AlertSeverity>('MEDIUM', { nonNullable: true }),
    cooldownSeconds: new FormControl<number | null>(DEFAULT_COOLDOWN_SECONDS, {
      validators: [
        Validators.required,
        Validators.min(COOLDOWN_MIN_SECONDS),
        Validators.max(COOLDOWN_MAX_SECONDS),
        wholeNumber,
      ],
    }),
    channel: new FormControl<AlertChannel>('WEBHOOK', { nonNullable: true }),
    target: new FormControl('', { nonNullable: true, validators: [targetValidator] }),
    enabled: new FormControl(true, { nonNullable: true }),
  });
  applyConditionFields(form);
  return form;
}

/** The fields of the other condition are disabled: they are neither validated nor sent. */
export function applyConditionFields(form: RuleForm): void {
  const threshold = form.controls.condition.value === 'THRESHOLD';
  setEnabled(form.controls.operator, threshold);
  setEnabled(form.controls.threshold, threshold);
  setEnabled(form.controls.minSeverity, !threshold);
}

function setEnabled(control: AbstractControl, enabled: boolean): void {
  if (enabled && control.disabled) {
    control.enable({ emitEvent: false });
  } else if (!enabled && control.enabled) {
    control.disable({ emitEvent: false });
  }
}

/**
 * Keeps the dependent fields in step with the choices that drive them:
 * - another condition shows its own fields and puts the fields it hides back to their defaults;
 * - another channel clears the target (an address is not a recipient label, and the old value would
 *   otherwise be sent to the wrong kind of receiver) and re-validates it.
 */
export function wireRuleForm(form: RuleForm, silent: () => boolean = () => false): Subscription {
  const subscription = new Subscription();
  subscription.add(
    form.controls.condition.valueChanges.subscribe(() => {
      applyConditionFields(form);
      if (silent()) {
        return;
      }
      if (form.controls.condition.value === 'THRESHOLD') {
        form.controls.minSeverity.reset('MEDIUM', { emitEvent: false });
      } else {
        form.controls.operator.reset('GT', { emitEvent: false });
        form.controls.threshold.reset(null, { emitEvent: false });
      }
      form.updateValueAndValidity();
    }),
  );
  subscription.add(
    form.controls.channel.valueChanges.subscribe(() => {
      if (silent()) {
        return;
      }
      form.controls.target.reset('', { emitEvent: false });
      form.controls.target.updateValueAndValidity();
    }),
  );
  return subscription;
}

/** Values of a saved rule as form values. The service is found later, from the metric. */
export function ruleToFormValue(rule: AlertRule): ReturnType<RuleForm['getRawValue']> {
  return {
    name: rule.name,
    serviceId: '',
    metricId: rule.metricId,
    condition: rule.condition,
    operator: rule.operator ?? 'GT',
    threshold: rule.threshold,
    minSeverity: rule.minSeverity ?? 'MEDIUM',
    cooldownSeconds: rule.cooldownSeconds,
    channel: rule.channel,
    target: rule.target,
    enabled: rule.enabled,
  };
}

type RawRuleForm = ReturnType<RuleForm['getRawValue']>;

/**
 * The request body for the form values. Only the fields of the chosen condition are included, as the
 * contract requires, and text is trimmed.
 */
export function buildRuleRequest(raw: RawRuleForm): AlertRuleRequest {
  const base = {
    name: raw.name.trim(),
    metricId: raw.metricId,
    channel: raw.channel,
    target: raw.target.trim(),
    cooldownSeconds: raw.cooldownSeconds ?? DEFAULT_COOLDOWN_SECONDS,
    enabled: raw.enabled,
  };
  if (raw.condition === 'THRESHOLD') {
    return {
      ...base,
      condition: 'THRESHOLD',
      operator: raw.operator,
      threshold: raw.threshold ?? 0,
    };
  }
  return { ...base, condition: 'ANOMALY_SEVERITY', minSeverity: raw.minSeverity };
}

export function buildUpdateRequest(raw: RawRuleForm, version: number): AlertRuleUpdateRequest {
  return { ...buildRuleRequest(raw), enabled: raw.enabled, version };
}

/** Server field names are JSON pointers ("/target") or bean paths ("target"); both address a control. */
export function controlNameOf(field: string): string {
  return field.replace(/^\//, '').split('/')[0];
}

/**
 * Puts the server's field errors on the matching controls (the message is the server's short
 * validation text, shown next to the field) and returns the text for the form as a whole when no field
 * could be named.
 */
export function applyRuleProblem(form: RuleForm, problem: ApiProblem): string | null {
  let placed = 0;
  const place = (name: string, message: string): void => {
    const control = form.get(name);
    if (control) {
      control.setErrors({ server: message });
      control.markAsTouched();
      placed += 1;
    }
  };
  for (const { field, message } of problem.fieldErrors) {
    place(controlNameOf(field), message);
  }
  if (problem.status === 409 && problem.kind === 'conflict') {
    place('name', 'A rule with this name already exists. Choose another name.');
  }
  return placed > 0 ? null : describeProblem(problem);
}

/** Controls in the order they appear, for moving focus to the first invalid one. */
export const FOCUS_ORDER: readonly (keyof RuleFormControls)[] = [
  'name',
  'serviceId',
  'metricId',
  'condition',
  'operator',
  'threshold',
  'minSeverity',
  'cooldownSeconds',
  'channel',
  'target',
];

export function firstInvalidControl(form: RuleForm): keyof RuleFormControls | null {
  return FOCUS_ORDER.find((name) => form.controls[name].invalid) ?? null;
}
