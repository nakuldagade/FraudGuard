// Ingestion load: POST /api/transactions with unique idempotency keys, ramping to RATE requests/second.
// Used for (a) the ingestion test and (b) the end-to-end test (the runner then reads pipeline latency from Prometheus).
// SYNTHETIC traffic from one laptop; see docs/performance.md for what the numbers do and do not mean.
import http from 'k6/http';
import { check } from 'k6';

const RATE = Number(__ENV.RATE || 50);
const RAMP = __ENV.RAMP || '1m';
const HOLD = __ENV.HOLD || '4m';
const BASE = __ENV.BASE_URL || 'http://host.docker.internal:8080';

export const options = {
  scenarios: {
    ingest: {
      executor: 'ramping-arrival-rate',
      startRate: 1, timeUnit: '1s', preAllocatedVUs: 50, maxVUs: 300,
      stages: [{ target: RATE, duration: RAMP }, { target: RATE, duration: HOLD }],
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],          // errors below 1%
    http_req_duration: ['p(95)<300'],        // p95 below 300 ms
  },
};

export default function () {
  // ~200 accounts spread the load over the Kafka partitions; the key is unique per request
  const account = `load-acc-${__VU % 200}`;
  const res = http.post(`${BASE}/api/transactions`, JSON.stringify({
    accountId: account, merchantId: `m-${1 + (__ITER % 8)}`, transactionType: 'PAYMENT',
    amount: Math.round((10 + Math.random() * 400) * 100) / 100, currency: 'INR', country: 'IN',
    deviceId: `dev-${__VU}`,
  }), {
    headers: {
      'Content-Type': 'application/json',
      'X-API-Key': __ENV.INGEST_API_KEY,
      'Idempotency-Key': `load-${__ENV.RUN_ID}-${__VU}-${__ITER}`,
    },
    tags: { name: 'POST /api/transactions' },
  });
  check(res, { 'accepted (202)': (r) => r.status === 202 });
}
