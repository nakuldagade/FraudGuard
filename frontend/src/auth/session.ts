// The login session lives in sessionStorage: it survives a page reload but is gone when the tab closes.
// Trade-off (also in docs/security-notes): any script running on the page can read sessionStorage, so an
// XSS bug would expose the token. The alternative, an httpOnly cookie, would need CSRF protection instead.
// Storage can be unavailable (private windows, blocked cookies), so every access is wrapped.
export interface Session {
  accessToken: string;
  username: string;
  role: 'ANALYST' | 'ADMIN';
  expiresAt: number; // epoch milliseconds
}

const KEY = 'fraudguard.session';

export function loadSession(): Session | null {
  try {
    const raw = sessionStorage.getItem(KEY);
    if (!raw) return null;
    const s = JSON.parse(raw) as Session;
    if (!s.accessToken || s.expiresAt <= Date.now()) {
      sessionStorage.removeItem(KEY);
      return null;
    }
    return s;
  } catch {
    return null;
  }
}

export function saveSession(s: Session): void {
  try {
    sessionStorage.setItem(KEY, JSON.stringify(s));
  } catch {
    /* without storage the user simply logs in again after a reload */
  }
}

export function clearSession(): void {
  try {
    sessionStorage.removeItem(KEY);
  } catch {
    /* nothing to clear */
  }
}

// api.ts calls this when the server answers 401; the AuthProvider listens and shows the login page.
type Listener = () => void;
const listeners = new Set<Listener>();
export const onUnauthorized = (fn: Listener) => {
  listeners.add(fn);
  return () => {
    listeners.delete(fn);
  };
};
export const notifyUnauthorized = () => listeners.forEach((fn) => fn());
