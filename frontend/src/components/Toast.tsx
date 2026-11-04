import { CheckIcon } from './Icons';

// A short confirmation that slides up from the bottom
export function Toast({ message }: { message: string | null }) {
  return (
    <div className="toast-zone" aria-live="polite">
      {message && <div className="toast" key={message}><CheckIcon /> {message}</div>}
    </div>
  );
}
