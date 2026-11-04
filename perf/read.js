// Read path: the dashboard's list and filter queries against a large table (seed it with perf/seed-100k.sql).
import http from 'k6/http';
import { check } from 'k6';

const BASE = __ENV.BASE_URL || 'http://host.docker.internal:8080';
const RATE = Number(__ENV.RATE || 20);

export const options = {
  scenarios: {
    read: { executor: 'constant-arrival-rate', rate: RATE, timeUnit: '1s', duration: __ENV.DURATION || '2m',
            preAllocatedVUs: 20, maxVUs: 100 },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    'http_req_duration{kind:list}': ['p(95)<200'],
    'http_req_duration{kind:filter}': ['p(95)<200'],
    'http_req_duration{kind:account}': ['p(95)<200'],
    'http_req_duration{kind:summary}': ['p(95)<200'],
  },
};

// One login for the whole run (the login endpoint is limited to 5 attempts a minute per IP)
export function setup() {
  const res = http.post(`${BASE}/api/auth/login`, JSON.stringify({
    username: __ENV.FRAUDGUARD_ADMIN_USERNAME, password: __ENV.FRAUDGUARD_ADMIN_PASSWORD,
  }), { headers: { 'Content-Type': 'application/json' } });
  check(res, { 'logged in': (r) => r.status === 200 });
  return { token: res.json('accessToken') };
}

const to = new Date().toISOString();
const from = new Date(Date.now() - 7 * 86400 * 1000).toISOString();

export default function (data) {
  const queries = [
    ['list', '/api/transactions?page=0&size=20'],
    ['list', `/api/transactions?page=${Math.floor(Math.random() * 50)}&size=20`],
    ['filter', '/api/transactions?status=REVIEW&reviewState=UNREVIEWED&size=20'],
    ['filter', '/api/transactions?status=BLOCKED&status=REVIEW&size=20'],
    ['filter', '/api/transactions?minRiskScore=40&maxRiskScore=90&size=20'],
    ['filter', `/api/transactions?from=${from}&to=${to}&status=REVIEW&size=20`],
    ['account', `/api/transactions?accountId=perf-acc-${Math.floor(Math.random() * 2000)}`],
    ['summary', '/api/transactions/summary'],
  ];
  const [kind, path] = queries[Math.floor(Math.random() * queries.length)];
  const res = http.get(`${BASE}${path}`, {
    headers: { Authorization: `Bearer ${data.token}` }, tags: { kind },
  });
  check(res, { 'status 200': (r) => r.status === 200 });
}
