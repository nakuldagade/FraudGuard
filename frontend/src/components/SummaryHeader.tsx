import { useAuth } from '../auth/useAuth';
import { humanize } from '../lib/format';
import { useCountUp } from '../hooks/useCountUp';
import type { DashboardSummary } from '../types';
import { BanIcon, CheckIcon, ClockIcon, EyeIcon, InboxIcon, ListIcon, LogOutIcon, ShieldIcon } from './Icons';
import { ThemeToggle } from './ThemeToggle';

export type View = 'dashboard' | 'operations';

interface Props {
  summary?: DashboardSummary;
  error?: string;
  view: View;
  onView: (view: View) => void;
}

// Top bar: brand, theme switch, who is logged in
function TopBar({ view, onView }: { view: View; onView: (v: View) => void }) {
  const { session, logout } = useAuth();
  return (
    <header className="topbar">
      <div className="brand">
        <div className="brand-mark"><ShieldIcon /></div>
        <div>
          <h1>FraudGuard</h1>
          <span>Analyst dashboard</span>
        </div>
      </div>
      {/* The Operations tab exists only for admins; an analyst sees no tabs at all */}
      {session?.role === 'ADMIN' && (
        <nav className="tabs" aria-label="Sections">
          <button type="button" className={view === 'dashboard' ? 'active' : ''} aria-current={view === 'dashboard' ? 'page' : undefined}
                  onClick={() => onView('dashboard')}>Dashboard</button>
          <button type="button" className={view === 'operations' ? 'active' : ''} aria-current={view === 'operations' ? 'page' : undefined}
                  onClick={() => onView('operations')}>Operations</button>
        </nav>
      )}
      <div className="topbar-right">
        <ThemeToggle />
        {session && (
          <div className="user-chip">
            <span className="avatar">{session.username.charAt(0).toUpperCase()}</span>
            <div className="user-text">
              <strong>{session.username}</strong>
              <span className={`role-pill role-${session.role.toLowerCase()}`}>{humanize(session.role)}</span>
            </div>
            <button type="button" className="icon-btn" onClick={logout} aria-label="Log out" title="Log out">
              <LogOutIcon />
            </button>
          </div>
        )}
      </div>
    </header>
  );
}

// One KPI card: icon, animated number, label and (optionally) its share of all transactions
function Kpi({ label, value, total, tone, icon, index }: {
  label: string; value?: number; total?: number; tone: string; icon: React.ReactNode; index: number;
}) {
  const shown = useCountUp(value);
  const pct = value !== undefined && total ? Math.round((value / total) * 100) : undefined;
  return (
    <div className={`kpi ${tone}`} style={{ ['--i' as string]: index }}>
      <div className="kpi-icon">{icon}</div>
      <div className="kpi-body">
        <div className="kpi-value">{value === undefined ? <span className="skeleton skel-num" /> : (shown ?? 0).toLocaleString('en-IN')}</div>
        <div className="kpi-label">{label}{pct !== undefined && <em>{pct}%</em>}</div>
      </div>
    </div>
  );
}

export function SummaryHeader({ summary, error, view, onView }: Props) {
  const by = (s: keyof NonNullable<typeof summary>['byStatus']) => (summary ? (summary.byStatus[s] ?? 0) : undefined);
  const total = summary?.total;
  return (
    <>
      <TopBar view={view} onView={onView} />
      {view === 'dashboard' && (
      <section className="kpis" aria-label="Summary">
        <Kpi index={0} label="Total" value={total} tone="neutral" icon={<ListIcon />} />
        <Kpi index={1} label="Approved" value={by('APPROVED')} total={total} tone="good" icon={<CheckIcon />} />
        <Kpi index={2} label="In review" value={by('REVIEW')} total={total} tone="warn" icon={<EyeIcon />} />
        <Kpi index={3} label="Blocked" value={by('BLOCKED')} total={total} tone="bad" icon={<BanIcon />} />
        <Kpi index={4} label="Pending" value={by('PENDING')} total={total} tone="neutral" icon={<ClockIcon />} />
        <Kpi index={5} label="Awaiting analyst" value={summary?.awaitingReview} tone="accent" icon={<InboxIcon />} />
      </section>
      )}
      {error && <div className="banner error page-banner">Could not load summary: {error}</div>}
    </>
  );
}
