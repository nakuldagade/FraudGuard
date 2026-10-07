import { useEffect, useState } from 'react';
import { fetchTransaction } from '../lib/api';
import type { TransactionDetail } from '../types';

// Loads the full detail (explanation, rule hits, ML score, review history)
// for the selected transaction. Pass null when nothing is selected.
// Re-fetches when the selection or `refreshKey` changes, so the panel
// updates after an analyst action.
export function useTransactionDetail(id: string | null, refreshKey: number) {
    const [state, setState] = useState<{
        id: string | null; // which transaction this result is for
        key: string; // which request (id + refreshKey) it answered
        data?: TransactionDetail;
        error?: string;
    }>({ id: null, key: '' });

    const key = id ? `${id}#${refreshKey}` : '';

    useEffect(() => {
        if (!id) return; // nothing selected, nothing to load
        let cancelled = false;
        fetchTransaction(id)
            .then((data) => {
                if (!cancelled) setState({ id, key, data });
            })
            .catch((e: Error) => {
                if (!cancelled) {
                    setState((prev) => ({
                        id,
                        key,
                        // keep old data only if it is for the same transaction
                        data: prev.id === id ? prev.data : undefined,
                        error: e.message,
                    }));
                }
            });
        return () => {
            cancelled = true;
        };
    }, [id, key]);

    // Never show a previous transaction's data under a new selection
    const current = id !== null && state.id === id;

    return {
        data: current ? state.data : undefined,
        error: current ? state.error : undefined,
        loading: id !== null && state.key !== key,
    };
}