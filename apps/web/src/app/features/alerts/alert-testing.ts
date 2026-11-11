import { AlertEvent, AlertRule } from './alert-models';

/** Test helpers: rule and event fixtures. Demo data only; not part of the app. */

let counter = 0;

function id(prefix: string): string {
  counter += 1;
  return `${prefix}-0000-4000-8000-${String(counter).padStart(12, '0')}`;
}

export const METRIC_ID = '22222222-2222-4222-8222-222222222222';
export const SERVICE_ID = '11111111-1111-4111-8111-111111111111';

export function rawRule(overrides: Partial<AlertRule> = {}): AlertRule {
  return {
    id: id('aaaaaaaa'),
    name: 'Latency too high',
    serviceId: SERVICE_ID,
    metricId: METRIC_ID,
    metricName: 'latency.p95',
    metricUnit: 'ms',
    serviceName: 'checkout',
    condition: 'THRESHOLD',
    operator: 'GT',
    threshold: 500,
    minSeverity: null,
    cooldownSeconds: 900,
    channel: 'WEBHOOK',
    target: 'https://hooks.example.com/pulseboard/secret-path?token=abc',
    enabled: true,
    version: 1,
    createdAt: '2026-05-01T10:00:00Z',
    updatedAt: '2026-05-01T10:00:00Z',
    lastTriggeredAt: null,
    createdBy: null,
    ...overrides,
  };
}

export function rawEvent(overrides: Partial<AlertEvent> = {}): AlertEvent {
  return {
    id: id('bbbbbbbb'),
    ruleId: '33333333-3333-4333-8333-333333333333',
    ruleName: 'Latency too high',
    sourceRef: '2026-05-01T12:00:00Z',
    triggeredAt: '2026-05-01T12:00:00Z',
    deliveryStatus: 'SENT',
    attempts: 1,
    deliveredAt: '2026-05-01T12:00:02Z',
    nextAttemptAt: null,
    lastError: null,
    payload: { rule: 'Latency too high', value: 612 },
    ...overrides,
  };
}

export function ruleProblem(
  kind: string,
  status: number,
  errors?: { field: string; message: string }[],
): [object, { status: number; statusText: string }] {
  return [
    {
      type: `urn:pulseboard:problem:${kind}`,
      title: 'Problem',
      status,
      ...(errors ? { errors } : {}),
    },
    { status, statusText: 'Error' },
  ];
}
