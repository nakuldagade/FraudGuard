import { useEffect, useState } from 'react';
import { fetchTrend } from '../lib/api';
import type { TrendPoint } from '../types';

// Hourly activity for the chart. Keeps the last good data if a refresh fails.
export function useTrend(refreshKey: number, hours = 24) {
  const [points, setPoints] = useState<TrendPoint[] | undefined>(undefined);
  const [error, setError] = useState<string | undefined>(undefined);

  useEffect(() => {
    let cancelled = false;
    fetchTrend(hours)
      .then((data) => {
        if (cancelled) return;
        setPoints(data);
        setError(undefined);
      })
      .catch((e: Error) => {
        if (!cancelled) setError(e.message);
      });
    return () => {
      cancelled = true;
    };
  }, [refreshKey, hours]);

  return { points, error };
}
