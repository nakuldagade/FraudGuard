import { useEffect, useState } from 'react';
import { useCountUp } from '../../hooks/useCountUp';

export interface Segment {
  label: string;
  value: number;
  color: string; // a CSS colour or variable, e.g. var(--good)
}

const R = 52;
const C = 2 * Math.PI * R; // circumference

// Ring chart: each segment is a stroked arc. The arcs "draw in" on first render (CSS transition on stroke-dasharray).
export function DonutChart({ segments, centerLabel }: { segments: Segment[]; centerLabel: string }) {
  const total = segments.reduce((sum, s) => sum + s.value, 0);
  const [drawn, setDrawn] = useState(false);
  const [hover, setHover] = useState<number | null>(null);
  const count = useCountUp(total);

  useEffect(() => {
    const id = requestAnimationFrame(() => setDrawn(true));
    return () => cancelAnimationFrame(id);
  }, []);

  const lengthOf = (value: number) => (total === 0 ? 0 : (value / total) * C);
  // Each arc starts where the previous ones end
  const arcs = segments.map((s, i) => ({
    ...s,
    i,
    length: lengthOf(s.value),
    offset: segments.slice(0, i).reduce((sum, prev) => sum + lengthOf(prev.value), 0),
  }));

  const active = hover !== null ? segments[hover] : null;

  return (
    <div className="donut">
      <svg viewBox="0 0 140 140" className="donut-svg" role="img"
           aria-label={`${centerLabel}: ${segments.map((s) => `${s.label} ${s.value}`).join(', ')}`}>
        <circle cx="70" cy="70" r={R} className="donut-track" />
        {arcs.map((a) => (
          <circle key={a.label} cx="70" cy="70" r={R} fill="none" stroke={a.color} strokeWidth="16"
                  strokeDasharray={`${drawn ? Math.max(a.length - 1.5, 0) : 0} ${C}`}
                  strokeDashoffset={-a.offset} transform="rotate(-90 70 70)"
                  className={`donut-arc ${hover !== null && hover !== a.i ? 'dim' : ''}`}
                  onMouseEnter={() => setHover(a.i)} onMouseLeave={() => setHover(null)} />
        ))}
        <text x="70" y="66" textAnchor="middle" className="donut-value">
          {(active ? active.value : count ?? 0).toLocaleString('en-IN')}
        </text>
        <text x="70" y="84" textAnchor="middle" className="donut-caption">{active ? active.label : centerLabel}</text>
      </svg>
      <ul className="legend">
        {segments.map((s, i) => (
          <li key={s.label} onMouseEnter={() => setHover(i)} onMouseLeave={() => setHover(null)}
              className={hover !== null && hover !== i ? 'dim' : ''}>
            <span className="legend-dot" style={{ background: s.color }} />
            <span className="legend-label">{s.label}</span>
            <span className="legend-value">{s.value.toLocaleString('en-IN')}</span>
            <span className="legend-pct">{total ? Math.round((s.value / total) * 100) : 0}%</span>
          </li>
        ))}
      </ul>
    </div>
  );
}
