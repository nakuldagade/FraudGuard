import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { API, page, server, tx } from './server';
import { renderApp, signIn } from './helpers';

describe('transaction list', () => {
  it('lists transactions with their decision and risk', async () => {
    signIn();
    renderApp();
    expect(await screen.findByText('acc-1')).toBeInTheDocument();
    expect(screen.getByText('acc-2')).toBeInTheDocument();
  });

  it('says so when no transaction matches', async () => {
    server.use(http.get(`${API}/api/transactions`, () => HttpResponse.json(page([]))));
    signIn();
    renderApp();
    expect(await screen.findByText('No transactions match these filters.')).toBeInTheDocument();
  });

  it('shows an error banner when the list cannot be loaded', async () => {
    server.use(http.get(`${API}/api/transactions`, () => new HttpResponse('boom', { status: 500 })));
    signIn();
    renderApp();
    expect(await screen.findByText(/Could not load transactions/)).toBeInTheDocument();
  });

  it('sends the decision filter to the backend and goes back to page 0', async () => {
    const seen: string[] = [];
    server.use(http.get(`${API}/api/transactions`, ({ request }) => {
      seen.push(new URL(request.url).search);
      return HttpResponse.json(page([tx(1)]));
    }));
    signIn();
    renderApp();
    await screen.findByText('acc-1');
    const selects = screen.getAllByRole('combobox');
    await userEvent.selectOptions(selects[0], 'REVIEW');
    await waitFor(() => expect(seen.some((q) => q.includes('status=REVIEW') && q.includes('page=0'))).toBe(true));
  });

  it('sends the account search after the typing pause', async () => {
    const seen: string[] = [];
    server.use(http.get(`${API}/api/transactions`, ({ request }) => {
      seen.push(new URL(request.url).search);
      return HttpResponse.json(page([tx(1)]));
    }));
    signIn();
    renderApp();
    await screen.findByText('acc-1');
    await userEvent.type(screen.getByPlaceholderText(/Account id/), 'acc-7');
    await waitFor(() => expect(seen.some((q) => q.includes('accountId=acc-7'))).toBe(true));
  });

  it('pages forward and back', async () => {
    const seen: string[] = [];
    server.use(http.get(`${API}/api/transactions`, ({ request }) => {
      const p = Number(new URL(request.url).searchParams.get('page'));
      seen.push(`page=${p}`);
      return HttpResponse.json(page([tx(p + 1)], p, 3));
    }));
    signIn();
    renderApp();
    await screen.findByText('acc-1');
    await userEvent.click(screen.getByRole('button', { name: /Next/ }));
    expect(await screen.findByText('acc-2')).toBeInTheDocument();
    expect(screen.getByText('Page 2 of 3')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: /Prev/ }));
    expect(await screen.findByText('acc-1')).toBeInTheDocument();
    expect(seen).toContain('page=1');
  });
});

describe('detail panel and review', () => {
  it('opens the detail of the clicked row', async () => {
    signIn();
    renderApp();
    await userEvent.click(await screen.findByText('acc-1'));
    expect(await screen.findByText('Sent to review because of velocity.')).toBeInTheDocument();
    expect(screen.getByText(/Too many transactions/)).toBeInTheDocument();
  });

  it('shows the rules-only note when there is no ML score', async () => {
    server.use(http.get(`${API}/api/transactions/:id/detail`, () =>
      HttpResponse.json({ transaction: tx(1, { anomalyScore: null, modelVersion: null }), decisionInputs: null, reviews: [] })));
    signIn();
    renderApp();
    await userEvent.click(await screen.findByText('acc-1'));
    expect(await screen.findByText(/used the rules only/)).toBeInTheDocument();
  });

  it('submits a review as the logged-in user, without sending a reviewer name', async () => {
    let body: Record<string, unknown> | null = null;
    server.use(http.post(`${API}/api/transactions/:id/review`, async ({ request }) => {
      body = (await request.json()) as Record<string, unknown>;
      return HttpResponse.json({ id: 'r1', action: 'CONFIRM_FRAUD', note: 'stolen card', reviewer: 'priya', createdAt: '2026-11-01T11:00:00Z' }, { status: 201 });
    }));
    signIn('ANALYST', 'priya');
    renderApp();
    await userEvent.click(await screen.findByText('acc-1'));
    expect(await screen.findByText('priya', { selector: 'strong' })).toBeInTheDocument();   // "Reviewing as priya"
    expect(screen.queryByPlaceholderText('Your name')).not.toBeInTheDocument();
    await userEvent.type(screen.getByPlaceholderText(/Note/), 'stolen card');
    await userEvent.click(screen.getByRole('button', { name: 'Confirm fraud' }));
    await waitFor(() => expect(body).toEqual({ action: 'CONFIRM_FRAUD', note: 'stolen card' }));
  });

  it('shows the server error when a review fails', async () => {
    server.use(http.post(`${API}/api/transactions/:id/review`, () => new HttpResponse('nope', { status: 500 })));
    signIn();
    renderApp();
    await userEvent.click(await screen.findByText('acc-1'));
    await userEvent.click(await screen.findByRole('button', { name: 'Mark legitimate' }));
    expect(await screen.findByText(/500/)).toBeInTheDocument();
  });
});

describe('model feedback panel', () => {
  const panel = () => screen.getByRole('heading', { name: 'Model feedback' }).closest('section')!;

  it('shows label counts, the live model and the retraining hint', async () => {
    signIn();
    renderApp();
    expect(await within(panel()).findByText('Retraining needs 60+ labels with 15+ of each class (have 8).')).toBeInTheDocument();
    expect(within(panel()).getAllByText('isoforest-v1').length).toBeGreaterThan(0);
  });

  it('degrades gracefully when the ML service is down: labels still show, the live model is unknown', async () => {
    server.use(http.get(`${API}/api/feedback/registry`, () => new HttpResponse(null, { status: 503 })));
    signIn();
    renderApp();
    expect(await within(panel()).findByText(/ML service unreachable/)).toBeInTheDocument();
    expect(within(panel()).getByText('Confirmed fraud')).toBeInTheDocument();
    expect(within(panel()).getByText(/Unavailable while the ML service is unreachable/)).toBeInTheDocument();
  });

  it('warns when the registry and the running service disagree', async () => {
    server.use(http.get(`${API}/api/feedback/registry`, () =>
      HttpResponse.json({ loaded: 'isoforest-v1', active: 'gbm-2', history: [] })));
    signIn();
    renderApp();
    expect(await within(panel()).findByText(/The registry says/)).toBeInTheDocument();
  });

  it('says there is enough feedback when the thresholds are met', async () => {
    server.use(http.get(`${API}/api/feedback/stats`, () =>
      HttpResponse.json({ confirmedFraud: 30, markedLegitimate: 40, furtherReviewRequested: 0, unreviewed: 0, usableLabels: 70 })));
    signIn();
    renderApp();
    expect(await within(panel()).findByText('Enough feedback to retrain.')).toBeInTheDocument();
  });

  it('lists promotions newest first', async () => {
    server.use(http.get(`${API}/api/feedback/registry`, () =>
      HttpResponse.json({ loaded: 'gbm-2', active: 'gbm-2', history: [
        { version: 'gbm-1', previous: 'isoforest-v1', promoted_at: '2026-10-30T10:00:00Z', pr_auc_gain: 0.12 },
        { version: 'gbm-2', previous: 'gbm-1', promoted_at: '2026-11-02T10:00:00Z', pr_auc_gain: 0.034 },
      ] })));
    signIn();
    renderApp();
    const items = await within(panel()).findAllByRole('listitem');
    expect(items[0]).toHaveTextContent('gbm-2');
    expect(items[0]).toHaveTextContent('+0.034');
  });
});
