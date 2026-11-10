// Accessibility audit of a served production build, in a real browser (axe-core, light and dark).
//
//   BASE=http://localhost:8081 node scripts/browser-audit.mjs
//
// It registers a throwaway workspace through the API, creates a service and a metric, ingests a
// series with a spike and waits (with a deadline, no blind sleeps) until the analytics service has
// produced anomalies, then scans /login, /register, /app/overview, /app/anomalies,
// /app/metrics/:id and the alert pages (rules, history with an event dialog, the rule editor for both
// conditions, a delete confirmation) in the light and the dark colour scheme. The exit code is 1 when axe reports a
// violation. System Chrome is used (CHROME_CHANNEL, default "chrome").
//
// VIEWPORT_WIDTH (default 1280) sets the browser width, so the narrow layout can be scanned with 390.
// SHOT_DIR, when set, receives a full page screenshot per scan, named SHOT_PREFIX-scheme-page.png.
import { AxeBuilder } from '@axe-core/playwright';
import { chromium } from 'playwright-core';

const BASE = process.env.BASE ?? 'http://localhost:8081';
const CHANNEL = process.env.CHROME_CHANNEL ?? 'chrome';
const DEADLINE_MS = Number(process.env.ANOMALY_DEADLINE_MS ?? 150_000);
const WIDTH = Number(process.env.VIEWPORT_WIDTH ?? 1280);
const SHOT_DIR = process.env.SHOT_DIR;
const SHOT_PREFIX = process.env.SHOT_PREFIX ?? 'audit';
const POINTS = 300;
const TAGS = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'];

async function api(method, path, { body, token, headers } = {}) {
  const response = await fetch(BASE + path, {
    method,
    headers: {
      'content-type': 'application/json',
      ...(token ? { authorization: `Bearer ${token}` } : {}),
      ...headers,
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await response.text();
  return { status: response.status, body: text ? JSON.parse(text) : null };
}

function gauss() {
  return Math.sqrt(-2 * Math.log(1 - Math.random())) * Math.cos(2 * Math.PI * Math.random());
}

async function seed() {
  const suffix = Math.random().toString(36).slice(2, 10);
  const email = `audit-${suffix}@example.com`;
  const password = `pw-${Math.random().toString(36).slice(2)}-${Math.random().toString(36).slice(2)}`;
  const registered = await api('POST', '/api/v1/auth/register', {
    body: { email, password, tenantName: `Audit ${suffix}`, tenantSlug: `audit-${suffix}` },
  });
  if (registered.status !== 201) throw new Error(`register failed: ${registered.status}`);
  const token = registered.body.accessToken;
  const service = await api('POST', '/api/v1/services', {
    token,
    body: { name: 'checkout', environment: 'PROD' },
  });
  const metric = await api('POST', `/api/v1/services/${service.body.id}/metrics`, {
    token,
    body: { name: 'latency.p95', unit: 'ms', aggregation: 'AVG' },
  });
  const key = await api('POST', '/api/v1/api-keys', { token, body: { name: 'audit' } });
  // The rules exist before the points, so the spike raises an alert (EMAIL_LOG needs no receiver).
  const rule = await api('POST', '/api/v1/alert-rules', {
    token,
    body: {
      name: 'Latency above 300 ms',
      metricId: metric.body.id,
      condition: 'THRESHOLD',
      operator: 'GT',
      threshold: 300,
      cooldownSeconds: 60,
      channel: 'EMAIL_LOG',
      target: 'oncall@example.com',
    },
  });
  if (rule.status !== 201) throw new Error(`alert rule failed: ${rule.status}`);
  await api('POST', '/api/v1/alert-rules', {
    token,
    body: {
      name: 'Any serious anomaly',
      metricId: metric.body.id,
      condition: 'ANOMALY_SEVERITY',
      minSeverity: 'LOW',
      cooldownSeconds: 300,
      channel: 'EMAIL_LOG',
      target: 'oncall@example.com',
      enabled: false,
    },
  });
  const end = Date.now() - 30_000;
  const points = Array.from({ length: POINTS }, (_, i) => ({
    metricId: metric.body.id,
    ts: new Date(end - (POINTS - 1 - i) * 60_000).toISOString(),
    value: Math.round((100 + gauss()) * 1e4) / 1e4,
  }));
  for (const offset of [6, 5]) points[POINTS - offset].value = 400 + offset;
  const ingest = await api('POST', '/api/v1/ingest', {
    headers: { 'x-api-key': key.body.key },
    body: { points, source: 'audit' },
  });
  if (ingest.status !== 202) throw new Error(`ingest failed: ${ingest.status}`);

  const started = Date.now();
  let anomalies = 0;
  while (Date.now() - started < DEADLINE_MS) {
    const page = await api('GET', `/api/v1/anomalies?metricId=${metric.body.id}&size=100`, {
      token,
    });
    anomalies = page.body?.totalItems ?? 0;
    if (anomalies > 0) break;
    await new Promise((resolve) => setTimeout(resolve, 3000));
  }
  // The dispatcher delivers the threshold alert within seconds.
  let alerts = 0;
  while (Date.now() - started < DEADLINE_MS) {
    const events = await api('GET', '/api/v1/alert-events?status=SENT', { token });
    alerts = events.body?.totalItems ?? 0;
    if (alerts > 0) break;
    await new Promise((resolve) => setTimeout(resolve, 2000));
  }
  console.log(
    `seeded: ${anomalies} anomalies, ${alerts} delivered alerts after ${Math.round((Date.now() - started) / 1000)}s`,
  );
  return {
    email,
    password,
    slug: `audit-${suffix}`,
    serviceId: service.body.id,
    metricId: metric.body.id,
    ruleId: rule.body.id,
  };
}

async function scan(page, name, scheme, results) {
  const axe = await new AxeBuilder({ page }).withTags(TAGS).analyze();
  const label = `${name} (${scheme})`;
  results.push({ label, violations: axe.violations, passes: axe.passes.length });
  if (SHOT_DIR) {
    const slug = name.replace(/[^a-z0-9]+/gi, '-').replace(/^-|-$/g, '');
    await page.screenshot({
      path: `${SHOT_DIR}/${SHOT_PREFIX}-${scheme}-${slug}.png`,
      fullPage: true,
    });
  }
  console.log(
    `${axe.violations.length === 0 ? 'ok  ' : 'FAIL'} ${label}: ${axe.violations.length} violations, ${axe.passes.length} rules passed`,
  );
  for (const violation of axe.violations) {
    console.log(`     ${violation.id} (${violation.impact}): ${violation.help}`);
    for (const node of violation.nodes.slice(0, 3)) console.log(`       ${node.target.join(' ')}`);
  }
}

const seeded = await seed();
const browser = await chromium.launch({ channel: CHANNEL });
const results = [];
try {
  for (const scheme of ['light', 'dark']) {
    const context = await browser.newContext({
      viewport: { width: WIDTH, height: 900 },
      colorScheme: scheme,
    });
    const page = await context.newPage();
    await page.goto(`${BASE}/login`);
    await page.getByRole('button', { name: 'Sign in' }).waitFor();
    await scan(page, '/login', scheme, results);

    await page.goto(`${BASE}/register`);
    await page.getByRole('button', { name: 'Create workspace' }).waitFor();
    await scan(page, '/register', scheme, results);

    await page.goto(`${BASE}/login`);
    await page.getByLabel('Workspace', { exact: false }).first().fill(seeded.slug);
    await page.getByLabel('Email', { exact: true }).fill(seeded.email);
    await page.getByLabel('Password', { exact: true }).fill(seeded.password);
    await page.getByRole('button', { name: 'Sign in' }).click();
    await page.waitForURL(/\/app\/overview/);

    await page.getByRole('list', { name: 'Services', exact: true }).waitFor();
    await page
      .getByText('open anomal', { exact: false })
      .first()
      .waitFor({ timeout: 20_000 })
      .catch(() => undefined);
    await scan(page, '/app/overview', scheme, results);

    await page.goto(`${BASE}/app/anomalies`);
    await page.getByRole('list', { name: 'Anomalies', exact: true }).waitFor();
    await scan(page, '/app/anomalies', scheme, results);

    await page.goto(`${BASE}/app/metrics/${seeded.metricId}`);
    await page.locator('canvas').first().waitFor();
    await page.waitForTimeout(600);
    await scan(page, '/app/metrics/:id', scheme, results);
    await page.getByRole('button', { name: 'Show data table' }).click();
    await scan(page, '/app/metrics/:id with table', scheme, results);

    await page.goto(`${BASE}/app/alerts/rules`);
    await page.getByRole('list', { name: 'Alert rules', exact: true }).waitFor();
    await scan(page, '/app/alerts/rules', scheme, results);
    await page.getByRole('button', { name: 'Delete Latency above 300 ms' }).click();
    await page.getByRole('dialog').waitFor();
    await scan(page, '/app/alerts/rules delete confirmation', scheme, results);
    await page.getByRole('button', { name: 'Cancel' }).click();

    await page.goto(`${BASE}/app/alerts/history`);
    await page.getByRole('list', { name: 'Alert events', exact: true }).waitFor();
    await scan(page, '/app/alerts/history', scheme, results);
    await page
      .getByRole('button', { name: /^Details of/ })
      .first()
      .click();
    await page.getByRole('dialog').waitFor();
    await scan(page, '/app/alerts/history event details', scheme, results);
    await page.keyboard.press('Escape');

    await page.goto(`${BASE}/app/alerts/rules/new`);
    await page.getByLabel('Rule name').waitFor();
    await scan(page, '/app/alerts/rules/new', scheme, results);
    await page.getByRole('radio', { name: 'An anomaly reaches a severity' }).check();
    await scan(page, '/app/alerts/rules/new anomaly severity', scheme, results);

    await page.goto(`${BASE}/app/alerts/rules/${seeded.ruleId}`);
    await page.getByLabel('Rule name').waitFor();
    await page.waitForFunction(
      () => document.querySelector('input[formcontrolname="name"]')?.value,
    );
    await scan(page, '/app/alerts/rules/:id', scheme, results);
    await context.close();
  }
} finally {
  await browser.close();
}
const failed = results.filter((result) => result.violations.length > 0);
console.log(`${results.length} scans, ${failed.length} with violations`);
process.exit(failed.length === 0 ? 0 : 1);
