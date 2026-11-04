import { useState } from 'react';
import type { TrendPoint } from '../../types';

const W = 640;
const H = 220;
const PAD = { top: 12, right: 8, bottom: 26, left: 34 };

// Stacked columns, one per hour: approved (bottom), review, blocked, pending. Hover a column to see the exact counts.
export function TrendChart({ points }: { points: TrendPoint[] }) {
  const [hover, setHover] = useState<number | null>(null);
  const totals = points.map((p) => p.approved + p.review + p.blocked + p.pending);
  const rawMax = Math.max(1, ...totals);
  const step = rawMax <= 5 ? 1 : Math.pow(10, Math.floor(Math.log10(rawMax)));
  const max = Math.ceil(rawMax / step) * step;                       // round the axis up to a tidy number
  const innerW = W - PAD.left - PAD.right;
  const innerH = H - PAD.top - PAD.bottom;
  const slot = innerW / Math.max(points.length, 1);
  const barW = Math.max(slot * 0.68, 2);
  const y = (v: number) => PAD.top + innerH - (v / max) * innerH;
  const ticks = [0, 0.25, 0.5, 0.75, 1].map((f) => Math.round(max * f));

  const hh = (iso: string) => new Date(iso).toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit', hour12: false });
  const active = hover !== null ? points[hover] : null;

  return (
    <div className="trend">
      <svg viewBox={`0 0 ${W} ${H}`} className="trend-svg" role="img" aria-label="Transactions per hour by decision">
        {ticks.map((t) => (
          <g key={t}>
            <line x1={PAD.left} x2={W - PAD.right} y1={y(t)} y2={y(t)} className="grid-line" />
            <text x={PAD.left - 6} y={y(t) + 3} textAnchor="end" className="axis-text">{t}</text>
          </g>
        ))}
        {points.map((p, i) => {
          const x = PAD.left + i * slot + (slot - barW) / 2;
          let acc = 0;
          const layers: [number, string][] = [[p.approved, 'var(--good)'], [p.review, 'var(--warn-solid)'], [p.blocked, 'var(--bad)'], [p.pending, 'var(--muted)']];
          return (
            <g key={p.hour} className={`col ${hover !== null && hover !== i ? 'dim' : ''}`} style={{ animationDelay: `${i * 22}ms` }}
               onMouseEnter={() => setHover(i)} onMouseLeave={() => setHover(null)}>
              <rect x={PAD.left + i * slot} y={PAD.top} width={slot} height={innerH} fill="transparent" />
              {layers.map(([value, color], k) => {
                if (value === 0) return null;
                const top = y(acc + value);
                const height = y(acc) - top;
                acc += value;
                return <rect key={k} x={x} y={top} width={barW} height={Math.max(height, 1)} rx="2" fill={color} />;
              })}
              {i % Math.ceil(points.length / 6) === 0 && (
                <text x={x + barW / 2} y={H - 8} textAnchor="middle" className="axis-text">{hh(p.hour)}</text>
              )}
            </g>
          );
        })}
      </svg>
      <div className="trend-foot">
        <ul className="legend inline">
          <li><span className="legend-dot" style={{ background: 'var(--good)' }} />Approved</li>
          <li><span className="legend-dot" style={{ background: 'var(--warn-solid)' }} />Review</li>
          <li><span className="legend-dot" style={{ background: 'var(--bad)' }} />Blocked</li>
        </ul>
        <div className="trend-tip" aria-live="polite">
          {active
            ? `${hh(active.hour)}: ${active.approved} approved, ${active.review} review, ${active.blocked} blocked${active.pending ? `, ${active.pending} pending` : ''}`
            : 'Hover a column for the exact counts'}
        </div>
      </div>
    </div>
  );
}
