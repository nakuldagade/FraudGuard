import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import type { Page, Transaction, TransactionDetail } from '../types';

export const API = 'http://localhost:8080';

export function tx(n: number, over: Partial<Transaction> = {}): Transaction {
  return {
    id: `0000000${n}-aaaa-bbbb-cccc-dddddddddddd`,
    accountId: `acc-${n}`,
    merchantId: 'm-1',
    transactionType: 'PAYMENT',
    amount: 100 * n,
    currency: 'INR',
    deviceId: 'dev-1',
    country: 'IN',
    occurredAt: '2026-11-01T10:00:00Z',
    status: 'REVIEW',
    riskScore: 45,
    ruleHits: [{ code: 'HIGH_VELOCITY', score: 40, reason: 'Too many transactions' }],
    anomalyScore: 0.71,
    modelVersion: 'isoforest-v1',
    policyVersion: 'policy-v1',
    reasonCodes: ['HIGH_VELOCITY'],
    decisionExplanation: 'Sent to review because of velocity.',
    reviewState: 'UNREVIEWED',
    createdAt: '2026-11-01T10:00:01Z',
    ...over,
  };
}

export const page = (rows: Transaction[], pageNo = 0, totalPages = 1): Page<Transaction> => ({
  content: rows,
  page: pageNo,
  size: 20,
  totalElements: rows.length * totalPages,
  totalPages,
});

export const detail = (t: Transaction): TransactionDetail => ({ transaction: t, decisionInputs: { ruleScore: 40 }, reviews: [] });

// Default fake API: a healthy backend. Individual tests override routes with server.use(...).
export const handlers = [
  http.post(`${API}/api/auth/login`, async ({ request }) => {
    const body = (await request.json()) as { username: string; password: string };
    if (body.password !== 'right-password') return new HttpResponse(null, { status: 401 });
    return HttpResponse.json({ accessToken: 'jwt-token', expiresInSeconds: 3600, username: body.username, role: 'ANALYST' });
  }),
  http.get(`${API}/api/transactions/summary`, () =>
    HttpResponse.json({ total: 3, awaitingReview: 2, byStatus: { REVIEW: 2, APPROVED: 1 }, byReviewState: {} })),
  http.get(`${API}/api/transactions/trend`, () =>
    HttpResponse.json(Array.from({ length: 24 }, (_, i) => ({
      hour: new Date(Date.UTC(2026, 10, 1, i)).toISOString(), approved: i, review: i % 3, blocked: i % 5, pending: 0,
    })))),
  http.get(`${API}/api/transactions`, () => HttpResponse.json(page([tx(1), tx(2)]))),
  http.get(`${API}/api/transactions/:id/detail`, ({ params }) => {
    const n = Number(String(params.id).charAt(7));
    return HttpResponse.json(detail(tx(n)));
  }),
  // Admin-only endpoints (the Operations tab)
  http.get(`${API}/api/admin/system-status`, () =>
    HttpResponse.json({ database: 'UP', redis: 'UP', mlCircuit: 'CLOSED', outboxPending: 0, stuckPending: 0, stuckAfterSeconds: 120, checkedAt: '2026-11-05T10:00:00Z' })),
  http.get(`${API}/api/admin/dlq/summary`, () => HttpResponse.json({ totalMessages: 0, recentByException: {}, recent: [] })),
  http.post(`${API}/api/admin/transactions/redrive-pending`, () => HttpResponse.json({ requeued: 3, skipped: 1 })),
  http.get(`${API}/api/feedback/labels`, () => HttpResponse.json([{ transactionId: 'a' }, { transactionId: 'b' }])),
  http.get(`${API}/api/feedback/stats`, () =>
    HttpResponse.json({ confirmedFraud: 3, markedLegitimate: 5, furtherReviewRequested: 1, unreviewed: 9, usableLabels: 8 })),
  http.get(`${API}/api/feedback/models`, () =>
    HttpResponse.json({ models: [{ modelVersion: 'isoforest-v1', transactions: 40, lastScoredAt: '2026-11-01T10:00:00Z' }], unscored: 2 })),
  http.get(`${API}/api/feedback/registry`, () =>
    HttpResponse.json({ loaded: 'isoforest-v1', active: 'isoforest-v1', history: [] })),
];

export const server = setupServer(...handlers);
