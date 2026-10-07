import { useEffect, useState } from 'react';
import { fetchSummary } from '../lib/api';
import type { DashboardSummary } from '../types';

// Loads the header numbers. Re-fetches whenever `refreshKey` changes
// (App bumps it after a review action and on a timer).
export function useSummary(refreshKey: number) {
    const [state, setState] = useState<{
        key: number; // which refreshKey this result belongs to
        data?: DashboardSummary;
        error?: string;
    }>({ key: -1 });

    useEffect(() => {
        let cancelled = false; // ignore results from outdated requests
        fetchSummary()
            .then((data) => {
                if (!cancelled) setState({ key: refreshKey, data });
            })
            .catch((e: Error) => {
                // keep the last good data on screen, just surface the error
                if (!cancelled) setState((prev) => ({ key: refreshKey, data: prev.data, error: e.message }));
            });
        return () => {
            cancelled = true;
        };
    }, [refreshKey]);

    return {
        data: state.data,
        error: state.error,
        loading: state.key !== refreshKey, // true until this refreshKey's response arrives
    };
}