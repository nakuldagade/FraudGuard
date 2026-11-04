import { useTrend } from '../hooks/useTrend';
import type { DashboardSummary } from '../types';
import { BarList } from './charts/BarList';
import { DonutChart } from './charts/DonutChart';
import { TrendChart } from './charts/TrendChart';

// Three chart cards: activity over time, the mix of decisions, and what analysts concluded.
export function Insights({ summary, refreshKey }: { summary?: DashboardSummary; refreshKey: number }) {
  const { points, error } = useTrend(refreshKey, 24);
  const s = summary?.byStatus ?? {};
  const r = summary?.byReviewState ?? {};

  return (
    <section className="insights" aria-label="Charts">
      <article className="card chart-card trend-card" style={{ ['--i' as string]: 0 }}>
        <header className="card-head">
          <h2>Activity</h2>
          <span className="muted">transactions per hour, last 24 hours</span>
        </header>
        {points ? <TrendChart points={points} /> : error ? <p className="muted">Chart unavailable: {error}</p> : <div className="skeleton chart-skeleton" />}
      </article>

      <article className="card chart-card" style={{ ['--i' as string]: 1 }}>
        <header className="card-head"><h2>Decisions</h2><span className="muted">all time</span></header>
        {summary ? (
          <DonutChart centerLabel="transactions" segments={[
            { label: 'Approved', value: s.APPROVED ?? 0, color: 'var(--good)' },
            { label: 'In review', value: s.REVIEW ?? 0, color: 'var(--warn-solid)' },
            { label: 'Blocked', value: s.BLOCKED ?? 0, color: 'var(--bad)' },
            { label: 'Pending', value: s.PENDING ?? 0, color: 'var(--muted)' },
          ]} />
        ) : <div className="skeleton chart-skeleton" />}
      </article>

      <article className="card chart-card" style={{ ['--i' as string]: 2 }}>
        <header className="card-head"><h2>Analyst verdicts</h2><span className="muted">fraud vs legitimate</span></header>
        {summary ? (
          <BarList items={[
            { label: 'Confirmed fraud', value: r.CONFIRMED_FRAUD ?? 0, color: 'var(--bad)' },
            { label: 'Marked legitimate', value: r.MARKED_LEGITIMATE ?? 0, color: 'var(--good)' },
            { label: 'Needs more review', value: r.NEEDS_MORE_REVIEW ?? 0, color: 'var(--warn-solid)' },
            { label: 'Unreviewed', value: r.UNREVIEWED ?? 0, color: 'var(--muted)' },
          ]} />
        ) : <div className="skeleton chart-skeleton" />}
      </article>
    </section>
  );
}
