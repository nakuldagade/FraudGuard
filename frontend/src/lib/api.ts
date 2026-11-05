import type {
  DashboardSummary,
  Page,
  ReviewAction,
  ReviewEntry,
  Transaction,
  TransactionDetail,
  TransactionFilters,
  TrendPoint,
  SystemStatus,
  RedriveResult,
  DlqSummary,
} from '../types';
import { loadSession, notifyUnauthorized } from '../auth/session';

const BASE = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080';

// Small wrapper: adds the login token, and throws a readable Error on any non-2xx response.
// A 401 means the token is missing, expired or invalid: the session is cleared and the login page shows.
export async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const token = loadSession()?.accessToken;
  const res = await fetch(`${BASE}${path}`, {
    ...init,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...init?.headers,
    },
  });
  if (res.status === 401) {
    notifyUnauthorized();
    throw new Error('Your session has ended. Please sign in again.');
  }
  if (!res.ok) {
    const text = await res.text().catch(() => '');
    throw new Error(`${res.status} ${res.statusText}${text ? `: ${text}` : ''}`);
  }
  return res.json() as Promise<T>;
}

// POST /api/auth/login. Not routed through request(): a wrong password is a normal 401 to show, not a lost session.
export async function loginRequest(username: string, password: string) {
  const res = await fetch(`${BASE}/api/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  });
  if (res.status === 401) throw new Error('Invalid username or password');
  if (res.status === 429) throw new Error('Too many attempts. Try again in a minute.');
  if (!res.ok) throw new Error(`Sign-in failed (${res.status})`);
  return (await res.json()) as {
    accessToken: string;
    expiresInSeconds: number;
    username: string;
    role: 'ANALYST' | 'ADMIN';
  };
}

// GET /api/transactions/summary
export const fetchSummary = () =>
  request<DashboardSummary>('/api/transactions/summary');

// GET /api/transactions/trend?hours=24 -> hourly counts by decision, for the activity chart
export const fetchTrend = (hours = 24) =>
  request<TrendPoint[]>(`/api/transactions/trend?hours=${hours}`);

// GET /api/transactions?status=&reviewState=&accountId=&page=&size=
// The backend filters by exact accountId (no free-text search), so the
// search box in the UI is sent as accountId.
export function fetchTransactions(f: TransactionFilters) {
  const params = new URLSearchParams();
  if (f.status) params.set('status', f.status);
  if (f.reviewState) params.set('reviewState', f.reviewState);
  if (f.search.trim()) params.set('accountId', f.search.trim());
  params.set('page', String(f.page));
  params.set('size', String(f.size));
  return request<Page<Transaction>>(`/api/transactions?${params}`);
}

// GET /api/transactions/{id}/detail -> transaction + decision inputs + review history
export const fetchTransaction = (id: string) =>
  request<TransactionDetail>(`/api/transactions/${id}/detail`);

// POST /api/transactions/{id}/review -> 201 with the saved review entry
export const submitReview = (
  id: string,
  body: { action: ReviewAction; note?: string }, // the reviewer is the logged-in user (the server reads it from the token)
) =>
  request<ReviewEntry>(`/api/transactions/${id}/review`, {
    method: 'POST',
    body: JSON.stringify(body),
  });


// ---------- Admin only (the backend answers 403 to an analyst) ----------

// GET /api/admin/system-status -> dependencies, ML circuit, backlogs
export const fetchSystemStatus = () => request<SystemStatus>('/api/admin/system-status');

// POST /api/admin/transactions/redrive-pending -> how many stuck transactions were re-queued
export const redrivePending = (olderThanSeconds: number) =>
  request<RedriveResult>(`/api/admin/transactions/redrive-pending?olderThanSeconds=${olderThanSeconds}&limit=500`, {
    method: 'POST',
  });

// GET /api/admin/dlq/summary -> dead-letter topic size and recent failure causes
export const fetchDlqSummary = () => request<DlqSummary>('/api/admin/dlq/summary');

// GET /api/feedback/labels -> every analyst-labelled transaction; saved as a JSON file in the browser
export async function downloadLabels(): Promise<number> {
  const rows = await request<unknown[]>('/api/feedback/labels');
  const blob = new Blob([JSON.stringify(rows, null, 2)], { type: 'application/json' });
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = `fraudguard-labels-${new Date().toISOString().slice(0, 10)}.json`;
  link.click();
  URL.revokeObjectURL(url);
  return rows.length;
}
