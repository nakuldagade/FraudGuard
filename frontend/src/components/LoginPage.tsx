import { useState, type FormEvent } from 'react';
import { useAuth } from '../auth/useAuth';

export function LoginPage() {
  const { login } = useAuth();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await login(username.trim(), password);
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="app" style={{ maxWidth: 360, margin: '80px auto' }}>
      <h1>FraudGuard</h1>
      <p style={{ color: 'var(--muted, #666)' }}>Analyst dashboard. Sign in to continue.</p>
      <form onSubmit={submit}>
        <label>
          Username
          <input
            type="text"
            autoComplete="username"
            required
            maxLength={64}
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            style={{ width: '100%', marginBottom: 8 }}
          />
        </label>
        <label>
          Password
          <input
            type="password"
            autoComplete="current-password"
            required
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            style={{ width: '100%', marginBottom: 8 }}
          />
        </label>
        <button className="btn" type="submit" disabled={busy || !username || !password}>
          {busy ? 'Signing in…' : 'Sign in'}
        </button>
        {error && <div className="banner error" style={{ marginTop: 8 }}>{error}</div>}
      </form>
    </div>
  );
}
