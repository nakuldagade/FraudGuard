import { useEffect, useRef, type ReactNode } from 'react';
import { CloseIcon } from './Icons';

// Side panel that slides in from the right over the list, with a dimmed backdrop. Esc or a click outside closes it.
export function Drawer({ title, closing, onClose, children }: {
  title: string; closing: boolean; onClose: () => void; children: ReactNode;
}) {
  const closeRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    closeRef.current?.focus();
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose();
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  return (
    <div className={`drawer-layer ${closing ? 'closing' : ''}`}>
      <div className="drawer-backdrop" onClick={onClose} />
      <aside className="drawer" role="dialog" aria-label={title}>
        <header className="drawer-head">
          <h2>{title}</h2>
          <button ref={closeRef} type="button" className="icon-btn" onClick={onClose} aria-label="Close details" title="Close (Esc)">
            <CloseIcon />
          </button>
        </header>
        <div className="drawer-body">{children}</div>
      </aside>
    </div>
  );
}
