import { formatDateTime, formatMoney, riskLevel } from '../lib/format';
import type { Transaction } from '../types';
import { InboxIcon } from './Icons';
import { ReviewBadge, StatusBadge } from './StatusBadge';

interface Props {
  rows: Transaction[] | undefined; // undefined = first load still running
  selectedId: string | null;
  onSelect: (id: string) => void;
}

export function TransactionTable({ rows, selectedId, onSelect }: Props) {
  if (!rows) {
    return (
      <div className="table-wrap" aria-busy="true" aria-label="Loading transactions">
        {Array.from({ length: 7 }, (_, i) => <div key={i} className="skeleton skel-row" />)}
      </div>
    );
  }
  if (rows.length === 0) {
    return (
      <div className="empty">
        <InboxIcon width={32} height={32} />
        <p>No transactions match these filters.</p>
      </div>
    );
  }

  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr>
            <th>Time</th>
            <th>Transaction</th>
            <th>Account</th>
            <th>Merchant</th>
            <th>Country</th>
            <th className="num">Amount</th>
            <th>Risk</th>
            <th>Decision</th>
            <th>Review</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((t, i) => (
            <tr
              key={t.id}
              style={{ ['--i' as string]: Math.min(i, 24) }}
              className={t.id === selectedId ? 'selected' : ''}
              onClick={() => onSelect(t.id)}
              // keyboard support: Tab to a row, Enter to open it
              tabIndex={0}
              onKeyDown={(e) => e.key === 'Enter' && onSelect(t.id)}
            >
              <td>{formatDateTime(t.occurredAt)}</td>
              <td className="mono">{t.id.slice(0, 8)}</td>
              <td className="mono">{t.accountId}</td>
              <td className="mono">{t.merchantId}</td>
              <td>{t.country}</td>
              <td className="num">{formatMoney(t.amount, t.currency)}</td>
              <td>
                {/* small bar + number, coloured by risk bucket */}
                <div className="risk">
                  <div className="risk-track">
                    <div
                      className={`risk-fill ${riskLevel(t.riskScore)}`}
                      style={{ width: `${Math.min(100, Math.max(0, t.riskScore))}%` }}
                    />
                  </div>
                  <span>{Math.round(t.riskScore)}</span>
                </div>
              </td>
              <td>
                <StatusBadge status={t.status} />
              </td>
              <td>
                <ReviewBadge state={t.reviewState} />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
