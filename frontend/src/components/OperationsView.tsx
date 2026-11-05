import { useCallback, useEffect, useState } from 'react';
import { downloadLabels, fetchDlqSummary, fetchSystemStatus, redrivePending } from '../lib/api';
import { formatDateTime } from '../lib/format';
import type { DlqSummary, SystemStatus } from '../types';
import { ChevronIcon } from './Icons';

// ADMIN-only tab: is everything reachable, what is stuck or failed, and the tools to recover. Every call here is also
// protected on the server (an analyst gets 403), hiding the tab is only a convenience.

type Tone = 'good' | 'bad' | 'warn' | 'neutral';

function Tile({ label, value, hint, tone, index }: { label: string; value: string; hint: string; tone: Tone; index: number }) {
  return (
    <div className={`status-tile ${tone}`} style={{ ['--i' as string]: index }}>
      <div className="status-head"><span className="status-dot" />{label}</div>
      <div className="status-value">{value}</div>
      <div className="status-hint">{hint}</div>
    </div>
  );
}

function StatusCard({ status, error }: { status?: SystemStatus; error?: string }) {
  if (error && !status) return <div className="banner error">Could not read the system status: {error}</div>;
  if (!status) return <div className="status-grid">{Array.from({ length: 5 }, (_, i) => <div key={i} className="skeleton status-skel" />)}</div>;

  const up = (v: string): Tone => (v === 'UP' ? 'good' : 'bad');
  const backlog = status.outboxPending;
  const stuck = status.stuckPending;
  return (
    <div className="status-grid">
      <Tile index={0} label="Database" value={status.database} tone={up(status.database)} hint="PostgreSQL" />
      <Tile index={1} label="Redis" value={status.redis} tone={up(status.redis)} hint="Velocity features" />
      <Tile index={2} label="ML circuit" value={status.mlCircuit} hint={status.mlCircuit === 'OPEN' ? 'Rules only for now' : status.mlCircuit === 'DISABLED' ? 'Scoring switched off' : 'ML scoring in use'}
            tone={status.mlCircuit === 'CLOSED' ? 'good' : status.mlCircuit === 'OPEN' ? 'bad' : 'neutral'} />
      <Tile index={3} label="Outbox backlog" value={backlog === null ? 'unknown' : backlog.toLocaleString('en-IN')} hint="Saved, not yet in Kafka"
            tone={backlog === null ? 'bad' : backlog > 100 ? 'warn' : 'good'} />
      <Tile index={4} label="Stuck PENDING" value={stuck === null ? 'unknown' : stuck.toLocaleString('en-IN')} hint={`PENDING over ${status.stuckAfterSeconds} s`}
            tone={stuck === null ? 'bad' : stuck > 0 ? 'bad' : 'good'} />
    </div>
  );
}

function RedrivePanel({ stuck, onDone, onToast }: { stuck: number | null | undefined; onDone: () => void; onToast: (m: string) => void }) {
  const [seconds, setSeconds] = useState(120);
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function run() {
    setBusy(true);
    setError(null);
    try {
      const r = await redrivePending(seconds);
      const text = `Re-queued ${r.requeued}, skipped ${r.skipped}`;
      setResult(text);
      onToast(text);
      onDone();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <article className="card ops-card" style={{ ['--i' as string]: 1 }}>
      <header className="card-head"><h2>Re-drive stuck transactions</h2>{stuck != null && <span className={`badge ${stuck > 0 ? 'status-blocked' : 'status-approved'}`}>{stuck} stuck</span>}</header>
      <p className="ops-text">
        If Redis or the database is down longer than the retry window, a transaction can stay PENDING. This writes a new event for each
        one, so the normal pipeline decides it. It is safe to repeat: decided transactions are not touched.
      </p>
      <div className="ops-row">
        <label className="inline-field">
          <span>Older than (seconds)</span>
          <input type="number" min={0} value={seconds} onChange={(e) => setSeconds(Math.max(0, Number(e.target.value)))} />
        </label>
        <button type="button" className="btn primary" disabled={busy} onClick={() => void run()}>{busy ? 'Re-driving…' : 'Re-drive stuck transactions'}</button>
      </div>
      {result && <div className="banner ok" role="status">{result}</div>}
      {error && <div className="banner error" role="alert">{error}</div>}
    </article>
  );
}

function DlqPanel({ summary, error, onRefresh }: { summary?: DlqSummary; error?: string; onRefresh: () => void }) {
  const causes = summary ? Object.entries(summary.recentByException).sort((a, b) => b[1] - a[1]) : [];
  return (
    <article className="card ops-card dlq" style={{ ['--i' as string]: 2 }}>
      <header className="card-head">
        <h2>Dead-letter topic</h2>
        <div className="head-actions">
          {summary && <span className={`badge ${summary.totalMessages > 0 ? 'status-review' : 'status-approved'}`}>{summary.totalMessages} event(s)</span>}
          <button type="button" className="btn ghost sm" onClick={onRefresh}>Refresh</button>
        </div>
      </header>
      <p className="ops-text">Events that failed all retries, or were malformed. Read-only: a person decides what to do with them.</p>
      {error && !summary && <div className="banner error">Could not read the dead-letter topic (is Kafka up?): {error}</div>}
      {!summary && !error && <div className="skeleton chart-skeleton" />}
      {summary && summary.totalMessages === 0 && <p className="empty-note">No dead-lettered events. Nothing has failed all its retries.</p>}
      {causes.length > 0 && (
        <div className="chips cause-chips">
          {causes.map(([name, n]) => <span key={name} className="badge status-blocked" title={name}>{name.split('.').pop()} ×{n}</span>)}
        </div>
      )}
      {summary && summary.recent.length > 0 && (
        <div className="table-wrap">
          <table className="mini-table">
            <thead><tr><th>When</th><th>Partition</th><th className="num">Offset</th><th>Account key</th><th>Cause</th></tr></thead>
            <tbody>
              {summary.recent.map((r) => (
                <tr key={`${r.partition}-${r.offset}`}>
                  <td>{formatDateTime(r.timestamp)}</td><td>{r.partition}</td><td className="num">{r.offset}</td>
                  <td className="mono">{r.key ?? '–'}</td><td className="mono">{r.exception.split('.').pop()}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </article>
  );
}

function ExportPanel({ onToast }: { onToast: (m: string) => void }) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function run() {
    setBusy(true);
    setError(null);
    try {
      const n = await downloadLabels();
      onToast(`Exported ${n.toLocaleString('en-IN')} labelled rows`);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <article className="card ops-card" style={{ ['--i' as string]: 3 }}>
      <header className="card-head"><h2>Training data</h2></header>
      <p className="ops-text">
        Downloads every transaction an analyst labelled (confirmed fraud or legitimate) with its stored decision inputs, as a JSON file. This is what
        retraining learns from. In this demo the labels are synthetic.
      </p>
      <div className="ops-row">
        <button type="button" className="btn" disabled={busy} onClick={() => void run()}>
          {busy ? 'Preparing…' : 'Download labelled data'} <ChevronIcon width={14} height={14} className="down" />
        </button>
      </div>
      {error && <div className="banner error" role="alert">{error}</div>}
    </article>
  );
}

export function OperationsView({ refreshKey, onToast }: { refreshKey: number; onToast: (message: string) => void }) {
  const [status, setStatus] = useState<SystemStatus | undefined>();
  const [statusError, setStatusError] = useState<string | undefined>();
  const [dlq, setDlq] = useState<DlqSummary | undefined>();
  const [dlqError, setDlqError] = useState<string | undefined>();
  const [bump, setBump] = useState(0); // manual refresh (after a re-drive, or the Refresh button)

  // Status first, on its own: the dead-letter call can take seconds when Kafka is down and must not hold the status back
  useEffect(() => {
    let cancelled = false;
    fetchSystemStatus()
      .then((s) => { if (!cancelled) { setStatus(s); setStatusError(undefined); } })
      .catch((e: Error) => { if (!cancelled) setStatusError(e.message); });
    return () => { cancelled = true; };
  }, [refreshKey, bump]);

  useEffect(() => {
    let cancelled = false;
    fetchDlqSummary()
      .then((d) => { if (!cancelled) { setDlq(d); setDlqError(undefined); } })
      .catch((e: Error) => { if (!cancelled) setDlqError(e.message); });
    return () => { cancelled = true; };
  }, [refreshKey, bump]);

  const refresh = useCallback(() => setBump((b) => b + 1), []);

  return (
    <section className="ops" aria-label="Operations">
      <header className="ops-title">
        <h2>Operations</h2>
        <span className="muted">Admin only. {status ? `Checked ${formatDateTime(status.checkedAt)}` : 'Checking…'}</span>
      </header>
      <StatusCard status={status} error={statusError} />
      <div className="ops-grid">
        <RedrivePanel stuck={status?.stuckPending} onDone={refresh} onToast={onToast} />
        <ExportPanel onToast={onToast} />
        <DlqPanel summary={dlq} error={dlqError} onRefresh={refresh} />
      </div>
    </section>
  );
}
