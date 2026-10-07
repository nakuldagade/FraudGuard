import { useState } from 'react';
import { submitReview } from '../lib/api';
import type { ReviewAction } from '../types';

interface Props {
  transactionId: string;
  onDone: () => void; // tell App to refresh the list, header and panel
}

const STORAGE_KEY = 'fraudguard.reviewer';

export function ReviewActions({ transactionId, onDone }: Props) {
  // Remember the analyst's name between visits (there is no login until Phase 10)
  const [reviewer, setReviewer] = useState(() => {
    try {
      return localStorage.getItem(STORAGE_KEY) ?? '';
    } catch {
      return '';
    }
  });
  const [note, setNote] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function act(action: ReviewAction) {
    if (!reviewer.trim()) {
      setError('Enter your name before submitting.');
      return;
    }
    setSubmitting(true);
    setError(null);
    try {
      await submitReview(transactionId, {
        action,
        reviewer: reviewer.trim(),
        note: note.trim() || undefined, // omit the field when empty
      });
      try {
        localStorage.setItem(STORAGE_KEY, reviewer.trim());
      } catch {
        /* storage can be unavailable; the name just won't be remembered */
      }
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
      <input
        type="text"
        placeholder="Your name"
        maxLength={64}
        value={reviewer}
        onChange={(e) => setReviewer(e.target.value)}
        style={{ width: '100%', marginBottom: 8 }}
      />
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
