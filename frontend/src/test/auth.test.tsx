import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { API, server } from './server';
import { renderApp, signIn } from './helpers';

describe('login and session handling', () => {
  it('shows the login page when there is no session', () => {
    renderApp();
    expect(screen.getByRole('button', { name: 'Sign in' })).toBeInTheDocument();
  });

  it('rejects a wrong password with a clear message and stays on the login page', async () => {
    renderApp();
    await userEvent.type(screen.getByLabelText('Username'), 'analyst');
    await userEvent.type(screen.getByLabelText('Password'), 'wrong');
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));
    expect(await screen.findByText('Invalid username or password')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Log out' })).not.toBeInTheDocument();   // no dashboard
  });

  it('logs in, shows the dashboard with the username and role, and keeps the token in sessionStorage', async () => {
    renderApp();
    await userEvent.type(screen.getByLabelText('Username'), 'analyst');
    await userEvent.type(screen.getByLabelText('Password'), 'right-password');
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));
    expect(await screen.findByText('analyst')).toBeInTheDocument();     // the user chip in the top bar
    expect(screen.getByText('Analyst')).toBeInTheDocument();             // the role
    expect(sessionStorage.getItem('fraudguard.session')).toContain('jwt-token');
  });

  it('shows a rate-limit message after a 429', async () => {
    server.use(http.post(`${API}/api/auth/login`, () => new HttpResponse(null, { status: 429 })));
    renderApp();
    await userEvent.type(screen.getByLabelText('Username'), 'a');
    await userEvent.type(screen.getByLabelText('Password'), 'b');
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));
    expect(await screen.findByText(/Too many attempts/)).toBeInTheDocument();
  });

  it('sends the bearer token on API calls', async () => {
    let header: string | null = null;
    server.use(http.get(`${API}/api/transactions`, ({ request }) => {
      header = request.headers.get('Authorization');
      return HttpResponse.json({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 });
    }));
    signIn();
    renderApp();
    await waitFor(() => expect(header).toBe('Bearer jwt-token'));
  });

  it('returns to the login page when the server answers 401 (expired token)', async () => {
    server.use(http.get(`${API}/api/transactions`, () => new HttpResponse(null, { status: 401 })));
    signIn();
    renderApp();
    expect(await screen.findByRole('button', { name: 'Sign in' })).toBeInTheDocument();
    expect(sessionStorage.getItem('fraudguard.session')).toBeNull();
  });

  it('logs out', async () => {
    signIn();
    renderApp();
    await userEvent.click(await screen.findByRole('button', { name: 'Log out' }));
    expect(screen.getByRole('button', { name: 'Sign in' })).toBeInTheDocument();
  });
});
