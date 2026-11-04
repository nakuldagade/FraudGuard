// Soak: steady low load for DURATION (default 30m) while the runner records the JVM heap, to see whether it grows.
import http from 'k6/http';
import { check } from 'k6';

const BASE = __ENV.BASE_URL || 'http://host.docker.internal:8080';

export const options = {
  scenarios: {
    soak: { executor: 'constant-arrival-rate', rate: Number(__ENV.RATE || 10), timeUnit: '1s',
            duration: __ENV.DURATION || '30m', preAllocatedVUs: 20, maxVUs: 100 },
  },
  thresholds: { http_req_failed: ['rate<0.01'], http_req_duration: ['p(95)<300'] },
};

export default function () {
  const res = http.post(`${BASE}/api/transactions`, JSON.stringify({
    accountId: `load-soak-${__ITER % 300}`, merchantId: 'm-1', transactionType: 'PAYMENT',
    amount: Math.round((10 + Math.random() * 400) * 100) / 100, currency: 'INR', country: 'IN',
  }), { headers: { 'Content-Type': 'application/json', 'X-API-Key': __ENV.INGEST_API_KEY,
                   'Idempotency-Key': `load-soak-${__ENV.RUN_ID}-${__VU}-${__ITER}` } });
  check(res, { 'accepted (202)': (r) => r.status === 202 });
}
