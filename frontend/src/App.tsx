import { useCallback, useEffect, useState } from 'react';
import { Filters } from './components/Filters';
import { Pagination } from './components/Pagination';
import { SummaryHeader } from './components/SummaryHeader';
import { TransactionTable } from './components/TransactionTable';
import { useSummary } from './hooks/useSummary';
import { useTransactions } from './hooks/useTransactions';
import type { TransactionFilters } from './types';
import { DetailPanel } from './components/DetailPanel';

const PAGE_SIZE = 20;
const REFRESH_MS = 10_000; // auto-refresh interval

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

  // Bumping this number makes the hooks re-fetch. Used by the timer below
  // and (in 2c) after an analyst submits a review.
  const [refreshKey, setRefreshKey] = useState(0);

  // Any filter change goes back to page 0, unless the patch sets `page` itself
  // (that's what the pagination buttons do).
  const updateFilters = useCallback((patch: Partial<TransactionFilters>) => {
    setFilters((f) => ({ ...f, page: 0, ...patch }));
  }, []);

  // Poll so new transactions appear without a manual reload
  useEffect(() => {
    const timer = setInterval(() => setRefreshKey((k) => k + 1), REFRESH_MS);
    return () => clearInterval(timer);
  }, []);

  const summary = useSummary(refreshKey);
  const list = useTransactions(filters, refreshKey);

  return (
      <div className="app">
        <SummaryHeader summary={summary.data} error={summary.error} />

        <main className="layout">
          <section className="list-pane">
            <Filters filters={filters} onChange={updateFilters} />

            {list.error && <div className="banner error">Could not load transactions: {list.error}</div>}

            <TransactionTable
                rows={list.data?.content}
                selectedId={selectedId}
                onSelect={setSelectedId}
            />

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

          <aside className="detail-pane">
            <DetailPanel
                id={selectedId}
                refreshKey={refreshKey}
                onReviewed={() => setRefreshKey((k) => k + 1)}
            />
          </aside>
        </main>
      </div>
  );
}