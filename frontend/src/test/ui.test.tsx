import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { API, server } from './server';
import { renderApp, signIn } from './helpers';

afterEach(() => {
  vi.unstubAllEnvs();
  document.documentElement.dataset.theme = 'light';
  localStorage.clear();
});

describe('demo accounts on the login page', () => {
  it('shows nothing when no demo variables are set', () => {
    renderApp();
    expect(screen.queryByRole('region', { name: 'Demo accounts' })).not.toBeInTheDocument();
  });

  it('shows the credentials and signs in with one click as the chosen role', async () => {
    vi.stubEnv('VITE_DEMO_ANALYST_USERNAME', 'analyst');
    vi.stubEnv('VITE_DEMO_ANALYST_PASSWORD', 'right-password');
    vi.stubEnv('VITE_DEMO_ADMIN_USERNAME', 'admin');
    vi.stubEnv('VITE_DEMO_ADMIN_PASSWORD', 'right-password');
    const logins: string[] = [];
    server.use(http.post(`${API}/api/auth/login`, async ({ request }) => {
      const body = (await request.json()) as { username: string };
      logins.push(body.username);
      return HttpResponse.json({ accessToken: 't', expiresInSeconds: 3600, username: body.username, role: body.username === 'admin' ? 'ADMIN' : 'ANALYST' });
    }));
    renderApp();

    const demo = screen.getByRole('region', { name: 'Demo accounts' });
    expect(within(demo).getAllByText('right-password')).toHaveLength(2);      // visible on purpose, for the demo
    await userEvent.click(within(demo).getByRole('button', { name: 'Sign in as Admin' }));

    expect(await screen.findByText('Admin')).toBeInTheDocument();             // the role pill in the top bar
    expect(logins).toEqual(['admin']);
  });

  it('"Fill form" puts the credentials in the form without signing in', async () => {
    vi.stubEnv('VITE_DEMO_ANALYST_USERNAME', 'analyst');
    vi.stubEnv('VITE_DEMO_ANALYST_PASSWORD', 'right-password');
    renderApp();
    await userEvent.click(screen.getByRole('button', { name: 'Fill form' }));
    expect(screen.getByLabelText('Username')).toHaveValue('analyst');
    expect(screen.getByLabelText('Password')).toHaveValue('right-password');
    expect(screen.getByRole('button', { name: 'Sign in' })).toBeInTheDocument();   // still on the login page
  });

  it('can be switched off', () => {
    vi.stubEnv('VITE_SHOW_DEMO_LOGINS', 'false');
    vi.stubEnv('VITE_DEMO_ANALYST_USERNAME', 'analyst');
    vi.stubEnv('VITE_DEMO_ANALYST_PASSWORD', 'right-password');
    renderApp();
    expect(screen.queryByRole('region', { name: 'Demo accounts' })).not.toBeInTheDocument();
  });
});

describe('theme', () => {
  it('toggles between light and dark and remembers the choice', async () => {
    signIn();
    renderApp();
    expect(document.documentElement.dataset.theme).toBe('light');
    await userEvent.click(await screen.findByRole('button', { name: 'Switch to dark theme' }));
    expect(document.documentElement.dataset.theme).toBe('dark');
    expect(localStorage.getItem('fraudguard.theme')).toBe('dark');
    await userEvent.click(screen.getByRole('button', { name: 'Switch to light theme' }));
    expect(document.documentElement.dataset.theme).toBe('light');
  });

  it('is also available on the login page', async () => {
    renderApp();
    await userEvent.click(screen.getByRole('button', { name: 'Switch to dark theme' }));
    expect(document.documentElement.dataset.theme).toBe('dark');
  });
});

describe('details drawer', () => {
  it('does not exist until a transaction is selected, and there is no "select a transaction" placeholder', async () => {
    signIn();
    renderApp();
    await screen.findByText('acc-1');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(screen.queryByText(/Select a transaction/)).not.toBeInTheDocument();
  });

  it('opens on a row click and closes with the close button', async () => {
    signIn();
    renderApp();
    await userEvent.click(await screen.findByText('acc-1'));
    expect(await screen.findByRole('dialog')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Close details' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  });

  it('closes with the Escape key', async () => {
    signIn();
    renderApp();
    await userEvent.click(await screen.findByText('acc-2'));
    await screen.findByRole('dialog');
    await userEvent.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  });
});

describe('charts and KPI cards', () => {
  it('draws the activity chart from the trend endpoint', async () => {
    signIn();
    renderApp();
    expect(await screen.findByRole('img', { name: 'Transactions per hour by decision' })).toBeInTheDocument();
  });

  it('draws the decision donut and the verdict bars from the summary', async () => {
    signIn();
    renderApp();
    const donut = await screen.findByRole('img', { name: /transactions: Approved 1, In review 2, Blocked 0, Pending 0/ });
    expect(donut).toBeInTheDocument();
    expect(screen.getByText('Analyst verdicts')).toBeInTheDocument();
  });

  it('shows the activity chart error instead of crashing when the trend call fails', async () => {
    server.use(http.get(`${API}/api/transactions/trend`, () => new HttpResponse('nope', { status: 500 })));
    signIn();
    renderApp();
    expect(await screen.findByText(/Chart unavailable/)).toBeInTheDocument();
    expect(await screen.findByText('acc-1')).toBeInTheDocument();             // the rest of the page still works
  });

  it('shows the share of each decision on its KPI card', async () => {
    signIn();
    renderApp();
    const kpis = await screen.findByRole('region', { name: 'Summary' });
    expect(await within(kpis).findByText('67%')).toBeInTheDocument();         // 2 of 3 in review
  });
});
