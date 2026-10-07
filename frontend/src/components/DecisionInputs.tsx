// Renders the stored "decision inputs" snapshot (velocity features, thresholds, ...)
// as a nested key/value list. It does not assume any field names, so it keeps
// working if the backend adds or renames inputs.

function Node({ value }: { value: unknown }) {
  if (value === null || value === undefined) return <span className="muted">–</span>;

  if (Array.isArray(value)) {
    if (value.length === 0) return <span className="muted">none</span>;
    return (
      <div className="json-tree">
        {value.map((item, i) => (
          <div className="json-row" key={i}>
            <span className="json-key">{i}</span>
            <Node value={item} />
          </div>
        ))}
      </div>
    );
  }

  if (typeof value === 'object') {
    const entries = Object.entries(value as Record<string, unknown>);
    if (entries.length === 0) return <span className="muted">none</span>;
    return (
      <div className="json-tree">
        {entries.map(([k, v]) => (
          <div className="json-row" key={k}>
            <span className="json-key">{k}</span>
            <Node value={v} />
          </div>
        ))}
      </div>
    );
  }

  // string / number / boolean
  return <span className="mono">{String(value)}</span>;
}

export function DecisionInputs({ data }: { data: unknown }) {
  return (
    <details>
      <summary>Decision inputs (stored snapshot)</summary>
      <Node value={data} />
    </details>
  );
}
