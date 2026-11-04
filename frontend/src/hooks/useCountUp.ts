import { useEffect, useRef, useState } from 'react';

// Animates a number from its previous value to the new one (ease-out). With "reduce motion" it jumps straight there.
export function useCountUp(target: number | undefined, duration = 800): number | undefined {
  const [shown, setShown] = useState<number | undefined>(undefined);
  const from = useRef(0);

  useEffect(() => {
    if (target === undefined) return;
    const reduce = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;
    const start = from.current;
    if (reduce || start === target) {
      from.current = target;
      const jump = requestAnimationFrame(() => setShown(target));
      return () => cancelAnimationFrame(jump);
    }
    const t0 = performance.now();
    let raf = 0;
    const tick = (now: number) => {
      const p = Math.min((now - t0) / duration, 1);
      const eased = 1 - Math.pow(1 - p, 3);
      setShown(Math.round(start + (target - start) * eased));
      if (p < 1) raf = requestAnimationFrame(tick);
      else from.current = target;
    };
    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, [target, duration]);

  return shown;
}
