import type { ReactNode } from 'react';
import { LoginPage } from '../components/LoginPage';
import { useAuth } from './useAuth';

// Route guard without a router: no session means the login page is all there is.
export function AuthGate({ children }: { children: ReactNode }) {
  const { session } = useAuth();
  return session ? <>{children}</> : <LoginPage />;
}
