import { useFeedback } from '../hooks/useFeedback';
import { formatDateTime } from '../lib/format';

// Retraining needs at least this much feedback. KEEP IN SYNC with MIN_LABELS / MIN_PER_CLASS in
// ml-service/training/retrain.py (the script is the real gate; this card only shows progress).
const MIN_LABELS = 60;
const MIN_PER_CLASS = 15;

export function FeedbackPanel({ refreshKey }: { refreshKey: number }) {
  const { stats, usage, registry, error, registryError } = useFeedback(refreshKey);

  const ready =
    !!stats &&
    stats.usableLabels >= MIN_LABELS &&
    stats.confirmedFraud >= MIN_PER_CLASS &&
    stats.markedLegitimate >= MIN_PER_CLASS;

  // Promotions, newest first
  const promotions = registry ? [...registry.history].reverse() : [];

  return (
    <section className="feedback">
      <h2>Model feedback</h2>

      {error && <div className="banner error">Could not load feedback data: {error}</div>}

      <div className="feedback-grid">
        {/* ---- Labels ---- */}
        <div>
          <h3>Analyst labels</h3>
          {stats ? (
            <>
              <dl>
                <dt>Confirmed fraud</dt>
                <dd>{stats.confirmedFraud}</dd>
                <dt>Marked legitimate</dt>
                <dd>{stats.markedLegitimate}</dd>
                <dt>Further review requested</dt>
                <dd>{stats.furtherReviewRequested}</dd>
                <dt>Unreviewed</dt>
                <dd>{stats.unreviewed}</dd>
              </dl>
              <p className={ready ? 'ok' : 'muted'}>
                {ready
                  ? 'Enough feedback to retrain.'
                  : `Retraining needs ${MIN_LABELS}+ labels with ${MIN_PER_CLASS}+ of each class (have ${stats.usableLabels}).`}
              </p>
            </>
          ) : (
            <p className="muted">Loading…</p>
          )}
        </div>

        {/* ---- Models that scored transactions ---- */}
        <div>
          <h3>Models in use</h3>
          {registry && (
            <p>
              Live now: <span className="badge status-approved">{registry.loaded}</span>
            </p>
          )}
          {registry && registry.active !== registry.loaded && (
            <div className="banner error">
              The registry says <strong>{registry.active}</strong> but the service is serving{' '}
              <strong>{registry.loaded}</strong>. Reload it:{' '}
              <code>curl -X POST http://127.0.0.1:8000/admin/reload</code>
            </div>
          )}
          {registryError && <p className="muted">Live model unknown: ML service unreachable.</p>}

          {usage ? (
            <table className="mini-table">
              <thead>
                <tr>
                  <th>Version</th>
                  <th className="num">Scored</th>
                  <th>Last used</th>
                </tr>
              </thead>
              <tbody>
                {usage.models.map((m) => (
                  <tr key={m.modelVersion}>
                    <td className="mono">{m.modelVersion}</td>
                    <td className="num">{m.transactions.toLocaleString('en-IN')}</td>
                    <td>{formatDateTime(m.lastScoredAt)}</td>
                  </tr>
                ))}
                <tr>
                  <td>No ML score</td>
                  <td className="num">{usage.unscored.toLocaleString('en-IN')}</td>
                  <td className="muted">rules only</td>
                </tr>
              </tbody>
            </table>
          ) : (
            <p className="muted">Loading…</p>
          )}
        </div>

        {/* ---- Promotion history ---- */}
        <div>
          <h3>Promotions</h3>
          {promotions.length === 0 ? (
            <p className="muted">
              {registry ? 'No model has been promoted yet.' : 'Unavailable while the ML service is unreachable.'}
            </p>
          ) : (
            <ul>
              {promotions.map((p) => (
                <li key={p.version}>
                  <span className="mono">{p.version}</span> replaced <span className="mono">{p.previous}</span>
                  <div className="muted">
                    {formatDateTime(p.promoted_at)} · PR-AUC gain {p.pr_auc_gain >= 0 ? '+' : ''}
                    {p.pr_auc_gain.toFixed(3)}
                  </div>
                </li>
              ))}
            </ul>
          )}
        </div>
      </div>
    </section>
  );
}
