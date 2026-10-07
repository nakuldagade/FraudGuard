import type {
  DashboardSummary,
  Page,
  ReviewAction,
  ReviewEntry,
  Transaction,
  TransactionDetail,
  TransactionFilters,
} from '../types';

const BASE = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080';

// Small wrapper: throws a readable Error on any non-2xx response
async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(`${BASE}${path}`, {
    headers: { 'Content-Type': 'application/json' },
    ...init,
  });
  if (!res.ok) {
    const text = await res.text().catch(() => '');
    throw new Error(`${res.status} ${res.statusText}${text ? `: ${text}` : ''}`);
  }
  return res.json() as Promise<T>;
}

// GET /api/transactions/summary
export const fetchSummary = () =>
  request<DashboardSummary>('/api/transactions/summary');

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
  body: { action: ReviewAction; reviewer: string; note?: string },
) =>
  request<ReviewEntry>(`/api/transactions/${id}/review`, {
    method: 'POST',
    body: JSON.stringify(body),
  });
