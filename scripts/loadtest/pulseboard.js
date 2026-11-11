// k6 load test for the Pulseboard stack. See docs/LOAD_TEST.md.
//
// setup() registers a throwaway tenant, creates one service with a few metrics and an API key. Two scenarios
// then run in parallel:
//   ingest: POST /api/v1/ingest with the API key (X-Api-Key), batches of BATCH_SIZE points
//   read:   GET /api/v1/metrics/{id}/points with the JWT (Authorization: Bearer)
// The default rates stay below the built-in rate limits of a FREE tenant (60 ingest batches per minute, 120
// metric queries per minute), so the run measures the service and not the limiter. Any 429 counts as a failed
// request. The access token lives 15 minutes, so keep DURATION below that.
import http from 'k6/http';
import { check, fail } from 'k6';
import { Counter } from 'k6/metrics';

const BASE_URL = (__ENV.BASE_URL || 'http://host.docker.internal:8081').replace(/\/$/, '');
const DURATION = __ENV.DURATION || '2m';
const INGEST_PER_MINUTE = Number(__ENV.INGEST_PER_MINUTE || 30);
const READ_PER_MINUTE = Number(__ENV.READ_PER_MINUTE || 60);
const BATCH_SIZE = Number(__ENV.BATCH_SIZE || 500);
const METRIC_COUNT = Number(__ENV.METRIC_COUNT || 5);
const P95_MS = Number(__ENV.P95_MS || 500);

const rateLimited = new Counter('rate_limited_responses');

// 429 and every other non-2xx response count as failed requests (http_req_failed).
http.setResponseCallback(http.expectedStatuses({ min: 200, max: 299 }));

export const options = {
  scenarios: {
    ingest: {
      executor: 'constant-arrival-rate',
      exec: 'ingest',
      rate: INGEST_PER_MINUTE,
      timeUnit: '1m',
      duration: DURATION,
      preAllocatedVUs: 5,
      maxVUs: 20,
    },
    read: {
      executor: 'constant-arrival-rate',
      exec: 'read',
      rate: READ_PER_MINUTE,
      timeUnit: '1m',
      duration: DURATION,
      preAllocatedVUs: 5,
      maxVUs: 20,
    },
  },
  thresholds: {
    'http_req_failed': ['rate<0.01'],
    'http_req_duration{scenario:ingest}': [`p(95)<${P95_MS}`],
    'http_req_duration{scenario:read}': [`p(95)<${P95_MS}`],
    'checks': ['rate>0.99'],
    'rate_limited_responses': ['count==0'],
  },
};

function json(res) {
  try {
    return res.json();
  } catch (e) {
    return null;
  }
}

export function setup() {
  const suffix = Math.random().toString(36).slice(2, 10);
  const password = `lt-${Math.random().toString(36).slice(2)}-${Math.random().toString(36).slice(2)}`;
  const jsonHeaders = { 'Content-Type': 'application/json' };

  const reg = http.post(
    `${BASE_URL}/api/v1/auth/register`,
    JSON.stringify({
      tenantSlug: `loadtest-${suffix}`,
      tenantName: `Load test ${suffix}`,
      email: `loadtest-${suffix}@example.com`,
      password,
    }),
    { headers: jsonHeaders },
  );
  if (reg.status !== 201) fail(`register failed: ${reg.status} ${reg.body}`);
  const token = json(reg).accessToken;
  const auth = { headers: { ...jsonHeaders, Authorization: `Bearer ${token}` } };

  const svc = http.post(`${BASE_URL}/api/v1/services`, JSON.stringify({ name: 'loadtest', environment: 'PROD' }), auth);
  if (svc.status !== 201) fail(`create service failed: ${svc.status} ${svc.body}`);
  const serviceId = json(svc).id;

  const metricIds = [];
  for (let i = 0; i < METRIC_COUNT; i++) {
    const m = http.post(
      `${BASE_URL}/api/v1/services/${serviceId}/metrics`,
      JSON.stringify({ name: `loadtest.metric${i}`, unit: 'ms', aggregation: 'AVG' }),
      auth,
    );
    if (m.status !== 201) fail(`create metric failed: ${m.status} ${m.body}`);
    metricIds.push(json(m).id);
  }

  const key = http.post(`${BASE_URL}/api/v1/api-keys`, JSON.stringify({ name: 'loadtest' }), auth);
  if (key.status !== 201) fail(`create API key failed: ${key.status} ${key.body}`);

  // Samples are spread over the hour before the start, one second apart per metric, so they stay in the
  // current partition and are never in the future. Re-sent timestamps overwrite the earlier sample.
  return { token, apiKey: json(key).key, metricIds, baseMs: Date.now() - 3600 * 1000 };
}

export function ingest(data) {
  const points = [];
  const start = __ITER * BATCH_SIZE;
  for (let i = 0; i < BATCH_SIZE; i++) {
    const n = start + i;
    const metricId = data.metricIds[n % data.metricIds.length];
    const second = Math.floor(n / data.metricIds.length) % 3500;
    points.push({
      metricId,
      ts: new Date(data.baseMs + second * 1000).toISOString(),
      value: 100 + Math.sin(n / 50) * 10 + Math.random() * 5,
    });
  }
  const res = http.post(`${BASE_URL}/api/v1/ingest`, JSON.stringify({ points, source: 'loadtest' }), {
    headers: { 'Content-Type': 'application/json', 'X-Api-Key': data.apiKey },
  });
  if (res.status === 429) rateLimited.add(1);
  check(res, {
    'ingest 202': (r) => r.status === 202,
    'ingest accepted all points': (r) => {
      const body = json(r);
      return body !== null && body.accepted === BATCH_SIZE && body.rejected.length === 0;
    },
  });
}

export function read(data) {
  const metricId = data.metricIds[Math.floor(Math.random() * data.metricIds.length)];
  const to = new Date();
  const from = new Date(to.getTime() - 2 * 3600 * 1000);
  const res = http.get(
    `${BASE_URL}/api/v1/metrics/${metricId}/points?from=${from.toISOString()}&to=${to.toISOString()}&step=PT1M`,
    { headers: { Authorization: `Bearer ${data.token}` }, tags: { name: 'GET /api/v1/metrics/{id}/points' } },
  );
  if (res.status === 429) rateLimited.add(1);
  check(res, { 'read 200': (r) => r.status === 200 });
}
