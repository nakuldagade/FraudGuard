import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react';
import { loginRequest } from '../lib/api';
import { AuthContext } from './authContext';
import { clearSession, loadSession, onUnauthorized, saveSession, type Session } from './session';

export function AuthProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<Session | null>(() => loadSession());

  const logout = useCallback(() => {
    clearSession();
    setSession(null);
  }, []);

  // A 401 from any API call (expired or invalid token) sends the user back to the login page
  useEffect(() => onUnauthorized(logout), [logout]);

  const login = useCallback(async (username: string, password: string) => {
    const r = await loginRequest(username, password);
    const next: Session = {
      accessToken: r.accessToken,
      username: r.username,
      role: r.role,
      expiresAt: Date.now() + r.expiresInSeconds * 1000,
    };
    saveSession(next);
    setSession(next);
  }, []);

  const value = useMemo(() => ({ session, login, logout }), [session, login, logout]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
