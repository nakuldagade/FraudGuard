import { useTransactionDetail } from '../hooks/useTransactionDetail';
import { formatDateTime, formatMoney, humanize, riskLevel } from '../lib/format';
import { DecisionInputs } from './DecisionInputs';
import { ReviewActions } from './ReviewActions';
import { ReviewBadge, StatusBadge } from './StatusBadge';

interface Props {
  id: string | null; // selected transaction, or null
  refreshKey: number; // bump to re-fetch
  onReviewed: () => void; // called after an analyst action succeeds
}

export function DetailPanel({ id, refreshKey, onReviewed }: Props) {
  const { data, error } = useTransactionDetail(id, refreshKey);

  if (!id) return <p className="muted">Select a transaction to see details.</p>;
  if (!data) {
    return error ? (
      <div className="banner error">Could not load details: {error}</div>
    ) : (
      <p className="muted">Loading…</p>
    );
  }

  const { transaction: t, decisionInputs, reviews } = data;

  return (
    <div className="detail">
      {error && <div className="banner error">Refresh failed: {error}</div>}

      <h2>
        Transaction <span className="mono">{t.id.slice(0, 8)}</span>
      </h2>
      <div className="chips">
        <StatusBadge status={t.status} />
        <ReviewBadge state={t.reviewState} />
      </div>

      <section>
        <h3>Payment</h3>
        <dl>
          <dt>Amount</dt>
          <dd>{formatMoney(t.amount, t.currency)}</dd>
          <dt>Type</dt>
          <dd>{humanize(t.transactionType)}</dd>
          <dt>Account</dt>
          <dd className="mono">{t.accountId}</dd>
          <dt>Merchant</dt>
          <dd className="mono">{t.merchantId}</dd>
          <dt>Device</dt>
          <dd className="mono">{t.deviceId}</dd>
          <dt>Country</dt>
          <dd>{t.country}</dd>
          <dt>Occurred</dt>
          <dd>{formatDateTime(t.occurredAt)}</dd>
        </dl>
      </section>

      <section>
        <h3>Decision</h3>
        <div className="risk">
          <div className="risk-track">
            <div
              className={`risk-fill ${riskLevel(t.riskScore)}`}
              style={{ width: `${Math.min(100, Math.max(0, t.riskScore))}%` }}
            />
          </div>
          <span>{Math.round(t.riskScore)} / 100</span>
        </div>
        <p style={{ marginTop: 8, fontSize: '0.875rem' }}>{t.decisionExplanation}</p>
        <div className="chips" style={{ marginTop: 8 }}>
          {t.reasonCodes.map((c) => (
            <span className="badge" key={c}>
              {c}
            </span>
          ))}
        </div>
        <dl style={{ marginTop: 8 }}>
          <dt>Policy</dt>
          <dd>{t.policyVersion}</dd>
        </dl>
      </section>

      <section>
        <h3>Rule hits</h3>
        {t.ruleHits.length === 0 ? (
          <p className="muted">No rules fired.</p>
        ) : (
          <ul>
            {t.ruleHits.map((h) => (
              <li key={h.code}>
                <strong>{h.code}</strong> (+{h.score}): {h.reason}
              </li>
            ))}
          </ul>
        )}
      </section>

      <section>
        <h3>ML score</h3>
        {t.anomalyScore === null ? (
          <p className="muted">Unavailable, so this decision used the rules only.</p>
        ) : (
          <dl>
            <dt>Anomaly score</dt>
            <dd>{t.anomalyScore.toFixed(3)}</dd>
            <dt>Model</dt>
            <dd>{t.modelVersion ?? '–'}</dd>
          </dl>
        )}
      </section>

      <section>
        <DecisionInputs data={decisionInputs} />
      </section>

      <section>
        <h3>Review history</h3>
        {reviews.length === 0 ? (
          <p className="muted">No analyst actions yet.</p>
        ) : (
          <ul className="review-list">
            {reviews.map((r) => (
              <li key={r.id}>
                <strong>{humanize(r.action)}</strong> by {r.reviewer}
                <div className="muted">{formatDateTime(r.createdAt)}</div>
                {r.note && <div>“{r.note}”</div>}
              </li>
            ))}
          </ul>
        )}
      </section>

      {/* key={t.id} resets the note box when you switch transactions */}
      <ReviewActions key={t.id} transactionId={t.id} onDone={onReviewed} />
    </div>
  );
}
