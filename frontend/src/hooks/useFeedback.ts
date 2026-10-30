import { useEffect, useState } from 'react';
import type { FeedbackStats, ModelUsage, RegistryInfo } from '../feedbackTypes';
import { fetchFeedbackStats, fetchModelUsage, fetchRegistry } from '../lib/feedbackApi';

interface FeedbackState {
  key: number; // which refreshKey this result belongs to
  stats?: FeedbackStats;
  usage?: ModelUsage;
  registry?: RegistryInfo;
  error?: string; // stats or usage failed (our own backend)
  registryError?: string; // the ML service could not be reached
}

// Loads the three feedback endpoints together. They are independent: if the ML service is down,
// the registry request fails but the label counts and model usage still show.
export function useFeedback(refreshKey: number) {
  const [state, setState] = useState<FeedbackState>({ key: -1 });

  useEffect(() => {
    let cancelled = false;
    Promise.allSettled([fetchFeedbackStats(), fetchModelUsage(), fetchRegistry()]).then(
      ([stats, usage, registry]) => {
        if (cancelled) return;
        setState((prev) => ({
          key: refreshKey,
          // keep the last good value if a refresh fails
          stats: stats.status === 'fulfilled' ? stats.value : prev.stats,
          usage: usage.status === 'fulfilled' ? usage.value : prev.usage,
          registry: registry.status === 'fulfilled' ? registry.value : undefined,
          error:
            stats.status === 'rejected'
              ? (stats.reason as Error).message
              : usage.status === 'rejected'
                ? (usage.reason as Error).message
                : undefined,
          registryError: registry.status === 'rejected' ? (registry.reason as Error).message : undefined,
        }));
      },
    );
    return () => {
      cancelled = true;
    };
  }, [refreshKey]);

  return state;
}
