import { humanize } from '../lib/format';
import type { ReviewState, TransactionStatus } from '../types';

// Coloured pill for the system decision (APPROVED / REVIEW / BLOCKED / PENDING).
// The colour comes from the CSS class "badge status-<value>" in index.css.
export function StatusBadge({ status }: { status: TransactionStatus }) {
    return <span className={`badge status-${status.toLowerCase()}`}>{humanize(status)}</span>;
}

// Pill for the analyst workflow state (Unreviewed / Confirmed fraud / ...)
export function ReviewBadge({ state }: { state: ReviewState }) {
    return <span className={`badge review-${state.toLowerCase()}`}>{humanize(state)}</span>;
}