// Shapes returned by the Phase 9 endpoints under /api/feedback

// GET /api/feedback/stats
export interface FeedbackStats {
  confirmedFraud: number; // latest analyst action = confirm fraud  -> label 1
  markedLegitimate: number; // latest analyst action = mark legitimate -> label 0
  furtherReviewRequested: number; // no label yet
  unreviewed: number; // no analyst action
  usableLabels: number; // confirmedFraud + markedLegitimate
}

// GET /api/feedback/models
export interface ModelUsageEntry {
  modelVersion: string;
  transactions: number;
  lastScoredAt: string; // ISO timestamp
}
export interface ModelUsage {
  models: ModelUsageEntry[]; // most recently used first
  unscored: number; // decided by rules only (ML service was unavailable)
}

// GET /api/feedback/registry (proxied from the ML service; field names are snake_case there)
export interface Promotion {
  version: string;
  previous: string;
  promoted_at: string;
  pr_auc_gain: number;
}
export interface RegistryInfo {
  loaded: string; // the model the ML service is serving right now
  active: string; // the model registry.json says should be active
  history: Promotion[]; // oldest first
}
