import { useEffect, useState } from 'react';
import { fetchTransactions } from '../lib/api';
import type { Page, TransactionFilters, Transaction } from '../types';

// Loads one page of transactions for the current filters.
// Re-fetches when the filters or `refreshKey` change.
export function useTransactions(filters: TransactionFilters, refreshKey: number) {
    const [state, setState] = useState<{
        key: string; // which request this result belongs to
        data?: Page<Transaction>;
        error?: string;
    }>({ key: '' });

    // One string that identifies "this exact request"
    const key = `${JSON.stringify(filters)}#${refreshKey}`;

    useEffect(() => {
        let cancelled = false; // ignore responses from outdated requests
        fetchTransactions(filters)
            .then((data) => {
                if (!cancelled) setState({ key, data });
            })
            .catch((e: Error) => {
                // keep the last good page on screen, just surface the error
                if (!cancelled) setState((prev) => ({ key, data: prev.data, error: e.message }));
            });
        return () => {
            cancelled = true;
        };
    }, [key, filters]);

    return {
        data: state.data,
        error: state.error,
        loading: state.key !== key,
    };
}