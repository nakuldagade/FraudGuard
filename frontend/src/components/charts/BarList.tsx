import { useEffect, useState } from 'react';
import type { Segment } from './DonutChart';

// Horizontal bars, one per item, scaled to the largest value. The bars grow in on first render.
export function BarList({ items }: { items: Segment[] }) {
  const max = Math.max(1, ...items.map((i) => i.value));
  const [shown, setShown] = useState(false);
  useEffect(() => {
    const id = requestAnimationFrame(() => setShown(true));
    return () => cancelAnimationFrame(id);
  }, []);

  return (
    <ul className="bars">
      {items.map((item) => (
        <li key={item.label}>
          <div className="bar-head">
            <span>{item.label}</span>
            <strong>{item.value.toLocaleString('en-IN')}</strong>
          </div>
          <div className="bar-track">
            <div className="bar-fill" style={{ width: shown ? `${(item.value / max) * 100}%` : '0%', background: item.color }} />
          </div>
        </li>
      ))}
    </ul>
  );
}
