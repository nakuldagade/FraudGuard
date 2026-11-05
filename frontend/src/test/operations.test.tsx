import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { API, server } from './server';
import { renderApp, signIn } from './helpers';

afterEach(() => vi.restoreAllMocks());

async function openOperations() {
  signIn('ADMIN', 'root');
  renderApp();
  await userEvent.click(await screen.findByRole('button', { name: 'Operations' }));
  return screen.findByRole('region', { name: 'Operations' });
}

describe('who sees the Operations tab', () => {
  it('an analyst sees no tabs at all', async () => {
    signIn('ANALYST', 'analyst');
    renderApp();
    await screen.findByText('acc-1');
    expect(screen.queryByRole('navigation', { name: 'Sections' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Operations' })).not.toBeInTheDocument();
  });

  it('an admin sees Dashboard and Operations and can switch between them', async () => {
    const ops = await openOperations();
    expect(ops).toBeInTheDocument();
    expect(screen.queryByRole('region', { name: 'Summary' })).not.toBeInTheDocument();     // the KPI cards belong to the dashboard
    await userEvent.click(screen.getByRole('button', { name: 'Dashboard' }));
    expect(await screen.findByRole('region', { name: 'Summary' })).toBeInTheDocument();
    expect(screen.queryByRole('region', { name: 'Operations' })).not.toBeInTheDocument();
  });
});

describe('system status', () => {
  it('shows each dependency and backlog', async () => {
    const ops = await openOperations();
    expect(await within(ops).findByText('Database')).toBeInTheDocument();
    expect(within(ops).getAllByText('UP')).toHaveLength(2);                                 // database and Redis
    expect(within(ops).getByText('CLOSED')).toBeInTheDocument();
    expect(within(ops).getByText('Stuck PENDING')).toBeInTheDocument();
  });

  it('flags a down dependency, an open ML circuit and stuck transactions', async () => {
    server.use(http.get(`${API}/api/admin/system-status`, () =>
      HttpResponse.json({ database: 'UP', redis: 'DOWN', mlCircuit: 'OPEN', outboxPending: 250, stuckPending: 4, stuckAfterSeconds: 120, checkedAt: '2026-11-05T10:00:00Z' })));
    const ops = await openOperations();
    expect(await within(ops).findByText('DOWN')).toBeInTheDocument();
    expect(within(ops).getByText('OPEN')).toBeInTheDocument();
    expect(within(ops).getByText('Rules only for now')).toBeInTheDocument();
    expect(within(ops).getByText('250')).toBeInTheDocument();
    expect(within(ops).getByText('4 stuck')).toBeInTheDocument();
  });

  it('shows an error when the status cannot be read, without breaking the rest of the tab', async () => {
    server.use(http.get(`${API}/api/admin/system-status`, () => new HttpResponse('nope', { status: 500 })));
    const ops = await openOperations();
    expect(await within(ops).findByText(/Could not read the system status/)).toBeInTheDocument();
    expect(within(ops).getByRole('button', { name: 'Re-drive stuck transactions' })).toBeInTheDocument();
  });
});

describe('re-drive', () => {
  it('re-drives with the default 120 seconds and reports the result', async () => {
    let query = '';
    server.use(http.post(`${API}/api/admin/transactions/redrive-pending`, ({ request }) => {
      query = new URL(request.url).search;
      return HttpResponse.json({ requeued: 3, skipped: 1 });
    }));
    const ops = await openOperations();
    await userEvent.click(await within(ops).findByRole('button', { name: 'Re-drive stuck transactions' }));
    expect((await within(ops).findAllByText('Re-queued 3, skipped 1')).length).toBeGreaterThan(0);
    expect(query).toContain('olderThanSeconds=120');
  });

  it('uses the number of seconds the admin typed', async () => {
    let query = '';
    server.use(http.post(`${API}/api/admin/transactions/redrive-pending`, ({ request }) => {
      query = new URL(request.url).search;
      return HttpResponse.json({ requeued: 0, skipped: 0 });
    }));
    const ops = await openOperations();
    const input = await within(ops).findByLabelText('Older than (seconds)');
    await userEvent.clear(input);
    await userEvent.type(input, '0');
    await userEvent.click(within(ops).getByRole('button', { name: 'Re-drive stuck transactions' }));
    await waitFor(() => expect(query).toContain('olderThanSeconds=0'));
  });

  it('shows the error when the call fails', async () => {
    server.use(http.post(`${API}/api/admin/transactions/redrive-pending`, () => new HttpResponse('boom', { status: 500 })));
    const ops = await openOperations();
    await userEvent.click(await within(ops).findByRole('button', { name: 'Re-drive stuck transactions' }));
    expect(await within(ops).findByRole('alert')).toHaveTextContent(/500/);
  });
});

describe('dead-letter topic', () => {
  it('says so when nothing has failed', async () => {
    const ops = await openOperations();
    expect(await within(ops).findByText(/No dead-lettered events/)).toBeInTheDocument();
  });

  it('lists the causes and the recent events', async () => {
    server.use(http.get(`${API}/api/admin/dlq/summary`, () => HttpResponse.json({
      totalMessages: 7,
      recentByException: { 'org.springframework.data.redis.RedisConnectionFailureException': 5, 'java.lang.IllegalArgumentException': 2 },
      recent: [{ partition: 1, offset: 42, timestamp: '2026-11-05T09:00:00Z', key: 'acc-9', exception: 'java.lang.IllegalArgumentException' }],
    })));
    const ops = await openOperations();
    expect(await within(ops).findByText('7 event(s)')).toBeInTheDocument();
    expect(within(ops).getByText('RedisConnectionFailureException ×5')).toBeInTheDocument();
    expect(within(ops).getByText('acc-9')).toBeInTheDocument();
    expect(within(ops).getByText('IllegalArgumentException', { selector: 'td' })).toBeInTheDocument();
  });

  it('explains when the topic cannot be read (Kafka down)', async () => {
    server.use(http.get(`${API}/api/admin/dlq/summary`, () => new HttpResponse('nope', { status: 500 })));
    const ops = await openOperations();
    expect(await within(ops).findByText(/is Kafka up\?/)).toBeInTheDocument();
  });
});

describe('training data export', () => {
  it('downloads the labels as a JSON file', async () => {
    const create = vi.fn(() => 'blob:fake');
    const revoke = vi.fn();
    Object.assign(URL, { createObjectURL: create, revokeObjectURL: revoke });
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});
    const ops = await openOperations();
    await userEvent.click(await within(ops).findByRole('button', { name: /Download labelled data/ }));
    expect(await screen.findByText('Exported 2 labelled rows')).toBeInTheDocument();
    expect(create).toHaveBeenCalledOnce();
    expect(click).toHaveBeenCalledOnce();
    expect(revoke).toHaveBeenCalledWith('blob:fake');
  });
});
