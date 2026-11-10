// Accessibility audit of a served production build, in a real browser (axe-core, light and dark).
//
//   BASE=http://localhost:8081 node scripts/browser-audit.mjs
//
// It registers a throwaway workspace through the API, creates a service and a metric, ingests a
// series with a spike and waits (with a deadline, no blind sleeps) until the analytics service has
// produced anomalies, then scans /login, /register, /app/overview, /app/anomalies and
// /app/metrics/:id in the light and the dark colour scheme. The exit code is 1 when axe reports a
// violation. System Chrome is used (CHROME_CHANNEL, default "chrome").
import { AxeBuilder } from '@axe-core/playwright';
import { chromium } from 'playwright-core';

const BASE = process.env.BASE ?? 'http://localhost:8081';
const CHANNEL = process.env.CHROME_CHANNEL ?? 'chrome';
const DEADLINE_MS = Number(process.env.ANOMALY_DEADLINE_MS ?? 150_000);
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
  console.log(`seeded: ${anomalies} anomalies after ${Math.round((Date.now() - started) / 1000)}s`);
  return {
    email,
    password,
    slug: `audit-${suffix}`,
    serviceId: service.body.id,
    metricId: metric.body.id,
  };
}

async function scan(page, name, scheme, results) {
  const axe = await new AxeBuilder({ page }).withTags(TAGS).analyze();
  const label = `${name} (${scheme})`;
  results.push({ label, violations: axe.violations, passes: axe.passes.length });
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
      viewport: { width: 1280, height: 900 },
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

    await page.goto(`${BASE}/app/metrics/${seeded.metricId}?service=${seeded.serviceId}`);
    await page.locator('canvas').first().waitFor();
    await page.waitForTimeout(600);
    await scan(page, '/app/metrics/:id', scheme, results);
    await page.getByRole('button', { name: 'Show data table' }).click();
    await scan(page, '/app/metrics/:id with table', scheme, results);
    await context.close();
  }
} finally {
  await browser.close();
}
const failed = results.filter((result) => result.violations.length > 0);
console.log(`${results.length} scans, ${failed.length} with violations`);
process.exit(failed.length === 0 ? 0 : 1);
