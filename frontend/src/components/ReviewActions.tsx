import { useState } from 'react';
import { useAuth } from '../auth/useAuth';
import { submitReview } from '../lib/api';
import type { ReviewAction } from '../types';

interface Props {
  transactionId: string;
  onDone: () => void; // tell App to refresh the list, header and panel
}

export function ReviewActions({ transactionId, onDone }: Props) {
  const { session } = useAuth(); // the reviewer is whoever is logged in; the server reads it from the token
  const [note, setNote] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function act(action: ReviewAction) {
    setSubmitting(true);
    setError(null);
    try {
      await submitReview(transactionId, {
        action,
        note: note.trim() || undefined, // omit the field when empty
      });
      setNote('');
      onDone();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <section>
      <h3>Analyst action</h3>
      <p style={{ margin: '0 0 8px', fontSize: 13 }}>Reviewing as <strong>{session?.username}</strong></p>
      <textarea
        placeholder="Note (optional, max 500 characters)"
        maxLength={500}
        rows={3}
        value={note}
        onChange={(e) => setNote(e.target.value)}
      />
      <div className="actions">
        <button className="btn danger" disabled={submitting} onClick={() => act('CONFIRM_FRAUD')}>
          Confirm fraud
        </button>
        <button className="btn success" disabled={submitting} onClick={() => act('MARK_LEGITIMATE')}>
          Mark legitimate
        </button>
        <button
          className="btn warning"
          disabled={submitting}
          onClick={() => act('REQUEST_FURTHER_REVIEW')}
        >
          Request further review
        </button>
      </div>
      {error && <div className="banner error" style={{ marginTop: 8 }}>{error}</div>}
    </section>
  );
}
