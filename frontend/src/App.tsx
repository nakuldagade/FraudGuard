import { useCallback, useEffect, useRef, useState } from 'react';
import { DetailPanel } from './components/DetailPanel';
import { Drawer } from './components/Drawer';
import { FeedbackPanel } from './components/FeedbackPanel';
import { Filters } from './components/Filters';
import { Insights } from './components/Insights';
import { Pagination } from './components/Pagination';
import { OperationsView } from './components/OperationsView';
import { SummaryHeader, type View } from './components/SummaryHeader';
import { Toast } from './components/Toast';
import { TransactionTable } from './components/TransactionTable';
import { useAuth } from './auth/useAuth';
import { useSummary } from './hooks/useSummary';
import { useTransactions } from './hooks/useTransactions';
import type { TransactionFilters } from './types';

const PAGE_SIZE = 20;
const REFRESH_MS = 10_000; // auto-refresh interval
const DRAWER_EXIT_MS = 220; // keep the drawer mounted this long so it can slide out

const INITIAL_FILTERS: TransactionFilters = {
  status: '',
  reviewState: '',
  search: '',
  page: 0,
  size: PAGE_SIZE,
};

export default function App() {
  const [filters, setFilters] = useState(INITIAL_FILTERS);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [closing, setClosing] = useState(false);
  const [toast, setToast] = useState<string | null>(null);
  const { session } = useAuth();
  const [chosenView, setView] = useState<View>('dashboard');
  // Only admins have the Operations tab: for anyone else the view is always the dashboard
  const view: View = session?.role === 'ADMIN' ? chosenView : 'dashboard';

  // Bumping this number makes the hooks re-fetch. Used by the timer below and after an analyst submits a review.
  const [refreshKey, setRefreshKey] = useState(0);

  const timers = useRef<number[]>([]);
  useEffect(() => () => timers.current.forEach(clearTimeout), []);

  // Any filter change goes back to page 0, unless the patch sets `page` itself (the pagination buttons do).
  const updateFilters = useCallback((patch: Partial<TransactionFilters>) => {
    setFilters((f) => ({ ...f, page: 0, ...patch }));
  }, []);

  // Poll so new transactions appear without a manual reload
  useEffect(() => {
    const timer = setInterval(() => setRefreshKey((k) => k + 1), REFRESH_MS);
    return () => clearInterval(timer);
  }, []);

  const select = (id: string) => {
    setClosing(false);
    setSelectedId(id);
  };

  // Slide out first, then unmount
  const closeDrawer = useCallback(() => {
    setClosing(true);
    timers.current.push(
      window.setTimeout(() => {
        setSelectedId(null);
        setClosing(false);
      }, DRAWER_EXIT_MS),
    );
  }, []);

  const showToast = useCallback((message: string) => {
    setToast(message);
    timers.current.push(window.setTimeout(() => setToast(null), 2600));
  }, []);

  const onReviewed = () => {
    setRefreshKey((k) => k + 1);
    showToast('Review recorded');
  };

  const summary = useSummary(refreshKey);
  const list = useTransactions(filters, refreshKey);

  return (
    <div className="app">
      <SummaryHeader summary={summary.data} error={summary.error} view={view} onView={setView} />
      {view === 'operations' ? (
        <div className="page"><OperationsView refreshKey={refreshKey} onToast={showToast} /></div>
      ) : (
      <div className="page">
        <Insights summary={summary.data} refreshKey={refreshKey} />
        <FeedbackPanel refreshKey={refreshKey} />

        <section className="card list-pane" style={{ ['--i' as string]: 3 }}>
          <header className="card-head list-head">
            <h2>Transactions</h2>
            <span className="muted">{list.data ? `${list.data.totalElements.toLocaleString('en-IN')} match` : ''}</span>
          </header>
          <Filters filters={filters} onChange={updateFilters} />

          {list.error && <div className="banner error">Could not load transactions: {list.error}</div>}

          <TransactionTable rows={list.data?.content} selectedId={selectedId} onSelect={select} />

          {list.data && (
            <Pagination
              page={list.data.page}
              size={list.data.size}
              totalPages={list.data.totalPages}
              totalElements={list.data.totalElements}
              onPage={(page) => updateFilters({ page })}
            />
          )}
        </section>
      </div>
      )}

      {selectedId && (
        <Drawer title="Transaction details" closing={closing} onClose={closeDrawer}>
          <DetailPanel id={selectedId} refreshKey={refreshKey} onReviewed={onReviewed} />
        </Drawer>
      )}
      <Toast message={toast} />
    </div>
  );
}
