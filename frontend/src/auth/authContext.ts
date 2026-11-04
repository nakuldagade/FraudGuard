import { createContext } from 'react';
import type { Session } from './session';

export interface AuthState {
  session: Session | null;
  login: (username: string, password: string) => Promise<void>;
  logout: () => void;
}

export const AuthContext = createContext<AuthState | null>(null);
