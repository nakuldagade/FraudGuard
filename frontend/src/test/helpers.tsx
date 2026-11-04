import { render } from '@testing-library/react';
import App from '../App';
import { AuthGate } from '../auth/AuthGate';
import { AuthProvider } from '../auth/AuthProvider';
import { saveSession } from '../auth/session';

export function signIn(role: 'ANALYST' | 'ADMIN' = 'ANALYST', username = 'analyst') {
  saveSession({ accessToken: 'jwt-token', username, role, expiresAt: Date.now() + 3_600_000 });
}

// The whole app exactly as main.tsx wires it: provider, login gate, dashboard
export const renderApp = () =>
  render(
    <AuthProvider>
      <AuthGate>
        <App />
      </AuthGate>
    </AuthProvider>,
  );
