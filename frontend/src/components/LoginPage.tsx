import { useState, type FormEvent } from 'react';
import { getDemoAccounts } from '../auth/demo';
import { useAuth } from '../auth/useAuth';
import { KeyIcon, ShieldIcon } from './Icons';
import { ThemeToggle } from './ThemeToggle';

export function LoginPage() {
  const { login } = useAuth();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState<string | null>(null); // 'form' or a demo role while a request is running
  const [error, setError] = useState<string | null>(null);
  const demo = getDemoAccounts();

  async function signIn(user: string, pass: string, who: string) {
    setBusy(who);
    setError(null);
    try {
      await login(user, pass);
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setBusy(null);
    }
  }

  function submit(e: FormEvent) {
    e.preventDefault();
    void signIn(username.trim(), password, 'form');
  }

  return (
    <div className="login-shell">
      <aside className="login-hero" aria-hidden="true">
        <div className="blob blob-a" /><div className="blob blob-b" />
        <div className="hero-content">
          <div className="brand-mark large"><ShieldIcon width={28} height={28} /></div>
          <h1>FraudGuard</h1>
          <p className="hero-sub">Real-time fraud decisions you can explain.</p>
          <ul className="hero-points">
            <li>Rules and an ML score, combined by a versioned policy</li>
            <li>Every decision comes with plain-English reasons</li>
            <li>Analyst verdicts retrain the model, behind a promotion gate</li>
          </ul>
          <p className="hero-note">Demo on synthetic data. Not production-grade.</p>
        </div>
      </aside>

      <main className="login-panel">
        <div className="login-top"><ThemeToggle /></div>
        <div className="login-card">
          <h2>Sign in</h2>
          <p className="muted">Analyst dashboard</p>
          <form onSubmit={submit}>
            <label className="field">
              <span>Username</span>
              <input type="text" autoComplete="username" required maxLength={64}
                     value={username} onChange={(e) => setUsername(e.target.value)} />
            </label>
            <label className="field">
              <span>Password</span>
              <input type="password" autoComplete="current-password" required
                     value={password} onChange={(e) => setPassword(e.target.value)} />
            </label>
            <button className="btn primary block" type="submit" disabled={busy !== null || !username || !password}>
              {busy === 'form' ? 'Signing in…' : 'Sign in'}
            </button>
          </form>
          {error && <div className="banner error" role="alert">{error}</div>}

          {demo.length > 0 && (
            <section className="demo" aria-label="Demo accounts">
              <div className="demo-title"><KeyIcon width={14} height={14} /> Demo accounts <span>one click to sign in</span></div>
              <div className="demo-grid">
                {demo.map((a) => (
                  <div className="demo-card" key={a.role}>
                    <div className="demo-head">
                      <span className="avatar sm">{a.label[0]}</span>
                      <div>
                        <strong>{a.label}</strong>
                        <small>{a.blurb}</small>
                      </div>
                    </div>
                    <dl>
                      <dt>Username</dt><dd><code>{a.username}</code></dd>
                      <dt>Password</dt><dd><code>{a.password}</code></dd>
                    </dl>
                    <div className="demo-actions">
                      <button type="button" className="btn primary sm" disabled={busy !== null}
                              onClick={() => void signIn(a.username, a.password, a.role)}>
                        {busy === a.role ? 'Signing in…' : `Sign in as ${a.label}`}
                      </button>
                      <button type="button" className="btn ghost sm" disabled={busy !== null}
                              onClick={() => { setUsername(a.username); setPassword(a.password); }}>
                        Fill form
                      </button>
                    </div>
                  </div>
                ))}
              </div>
              <p className="demo-warn">Shown for the demo only. Hide them with <code>VITE_SHOW_DEMO_LOGINS=false</code>.</p>
            </section>
          )}
        </div>
      </main>
    </div>
  );
}
