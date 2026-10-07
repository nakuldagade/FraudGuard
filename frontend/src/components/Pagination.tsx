interface Props {
    page: number; // 0-based, like Spring
    size: number;
    totalPages: number;
    totalElements: number;
    onPage: (page: number) => void;
}

export function Pagination({ page, size, totalPages, totalElements, onPage }: Props) {
    if (totalElements === 0) return null;

    // "1-20 of 134" style range text
    const from = page * size + 1;
    const to = Math.min((page + 1) * size, totalElements);

    return (
        <div className="pagination">
      <span>
        {from}–{to} of {totalElements.toLocaleString('en-IN')}
      </span>
            <div>
                <button className="btn" disabled={page === 0} onClick={() => onPage(page - 1)}>
                    ← Prev
                </button>
                <span className="page-num">
          Page {page + 1} of {Math.max(totalPages, 1)}
        </span>
                <button
                    className="btn"
                    disabled={page + 1 >= totalPages}
                    onClick={() => onPage(page + 1)}
                >
                    Next →
                </button>
            </div>
        </div>
    );
}