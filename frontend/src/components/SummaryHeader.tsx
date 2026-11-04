import { useAuth } from '../auth/useAuth';
import type { DashboardSummary } from '../types';

interface Props {
  summary?: DashboardSummary;
  error?: string;
}

// One stat card
function Stat({ label, value, tone }: { label: string; value: string; tone?: string }) {
  return (
    <div className={`stat ${tone ?? ''}`}>
      <div className="stat-value">{value}</div>
      <div className="stat-label">{label}</div>
    </div>
  );
}

// Top bar: title + the summary numbers
export function SummaryHeader({ summary, error }: Props) {
  const { session, logout } = useAuth();
  // Show "–" until the first response arrives; missing keys count as 0
  const n = (v?: number) => (v === undefined ? '–' : v.toLocaleString('en-IN'));
  const byStatus = (s: keyof NonNullable<typeof summary>['byStatus']) =>
    summary ? (summary.byStatus[s] ?? 0) : undefined;

  return (
    <header className="app-header">
      <div className="app-title">
        <h1>FraudGuard</h1>
        <span>Analyst dashboard</span>
      </div>

      <div className="stats">
        <Stat label="Total" value={n(summary?.total)} />
        <Stat label="Approved" value={n(byStatus('APPROVED'))} tone="good" />
        <Stat label="In review" value={n(byStatus('REVIEW'))} tone="warn" />
        <Stat label="Blocked" value={n(byStatus('BLOCKED'))} tone="bad" />
        <Stat label="Pending" value={n(byStatus('PENDING'))} />
        <Stat label="Awaiting analyst" value={n(summary?.awaitingReview)} tone="warn" />
      </div>

      {session && (
        <div className="whoami">
          <span>{session.username} ({session.role.toLowerCase()})</span>
          <button className="btn" onClick={logout}>Log out</button>
        </div>
      )}

      {error && <div className="banner error">Could not load summary: {error}</div>}
    </header>
  );
}
