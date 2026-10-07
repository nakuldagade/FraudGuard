import { useEffect, useState } from 'react';
import { humanize } from '../lib/format';
import type { ReviewState, TransactionFilters, TransactionStatus } from '../types';

const STATUSES: TransactionStatus[] = ['PENDING', 'APPROVED', 'REVIEW', 'BLOCKED'];
const REVIEW_STATES: ReviewState[] = [
    'UNREVIEWED',
    'CONFIRMED_FRAUD',
    'MARKED_LEGITIMATE',
    'NEEDS_MORE_REVIEW',
];

interface Props {
    filters: TransactionFilters;
    // Send only the fields that changed; App merges them and resets to page 0
    onChange: (patch: Partial<TransactionFilters>) => void;
}

export function Filters({ filters, onChange }: Props) {
    // The search box keeps its own text so typing is instant;
    // we only tell the parent after the user pauses for 300 ms (debounce),
    // otherwise every keystroke would hit the backend.
    const [text, setText] = useState(filters.search);

    useEffect(() => {
        const timer = setTimeout(() => {
            if (text !== filters.search) onChange({ search: text });
        }, 300);
        return () => clearTimeout(timer);
    }, [text, filters.search, onChange]);

    return (
        <div className="filters">
            <input
                type="search"
                placeholder="Account id (exact match)…"
                value={text}
                onChange={(e) => setText(e.target.value)}
            />

            <select
                value={filters.status}
                onChange={(e) => onChange({ status: e.target.value as TransactionStatus | '' })}
            >
                <option value="">All decisions</option>
                {STATUSES.map((s) => (
                    <option key={s} value={s}>
                        {humanize(s)}
                    </option>
                ))}
            </select>

            <select
                value={filters.reviewState}
                onChange={(e) => onChange({ reviewState: e.target.value as ReviewState | '' })}
            >
                <option value="">All review states</option>
                {REVIEW_STATES.map((s) => (
                    <option key={s} value={s}>
                        {humanize(s)}
                    </option>
                ))}
            </select>

            <button
                type="button"
                className="btn"
                onClick={() => {
                    setText('');
                    onChange({ status: '', reviewState: '', search: '' });
                }}
            >
                Clear
            </button>
        </div>
    );
}