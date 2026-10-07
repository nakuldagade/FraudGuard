// Shapes of the JSON returned by the Spring Boot backend (matched to the real API).

export type TransactionStatus = 'PENDING' | 'APPROVED' | 'REVIEW' | 'BLOCKED';

// Where the transaction stands in the analyst workflow (review_state column)
export type ReviewState =
  | 'UNREVIEWED'
  | 'CONFIRMED_FRAUD'
  | 'MARKED_LEGITIMATE'
  | 'NEEDS_MORE_REVIEW';

// What an analyst does (the three buttons). Not the same as ReviewState.
export type ReviewAction = 'CONFIRM_FRAUD' | 'MARK_LEGITIMATE' | 'REQUEST_FURTHER_REVIEW';

// One rule that fired (Phase 5 rule engine)
export interface RuleHit {
  code: string; // e.g. HIGH_VELOCITY
  score: number; // risk points this rule added
  reason: string; // human-readable explanation
}

// One transaction as returned by GET /api/transactions and inside the detail response
export interface Transaction {
  id: string;
  accountId: string;
  merchantId: string;
  transactionType: string; // e.g. PAYMENT
  amount: number;
  currency: string;
  deviceId: string;
  country: string;
  occurredAt: string; // ISO timestamp, when the payment happened
  status: TransactionStatus;
  riskScore: number; // final decision score, 0-100
  ruleHits: RuleHit[];
  anomalyScore: number | null; // ML score; null when the ML service was unavailable
  modelVersion: string | null;
  policyVersion: string; // e.g. policy-v1
  reasonCodes: string[];
  decisionExplanation: string;
  reviewState: ReviewState;
  createdAt: string;
}

// One analyst action in the audit trail (ReviewResponseDTO)
export interface ReviewEntry {
  id: string; // UUID
  action: ReviewAction;
  note: string | null;
  reviewer: string;
  createdAt: string;
}

// GET /api/transactions/{id}/detail (TransactionDetailDTO)
export interface TransactionDetail {
  transaction: Transaction;
  decisionInputs: unknown; // stored snapshot (velocity, rule hits, ML score, thresholds); shape not fixed
  reviews: ReviewEntry[]; // newest first
}

// Spring page envelope (only the fields we use). Note: current page is `page`.
export interface Page<T> {
  content: T[];
  page: number; // 0-based
  size: number;
  totalElements: number;
  totalPages: number;
}

// GET /api/transactions/summary
export interface DashboardSummary {
  total: number;
  awaitingReview: number;
  byStatus: Partial<Record<TransactionStatus, number>>;
  byReviewState: Partial<Record<ReviewState, number>>;
}

// Filters sent to the list endpoint
export interface TransactionFilters {
  status: TransactionStatus | '';
  reviewState: ReviewState | '';
  search: string;
  page: number;
  size: number;
}
