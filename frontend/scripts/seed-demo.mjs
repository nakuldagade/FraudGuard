// Fills the FraudGuard database with varied demo transactions by calling the
// real API, so every row is decided by your actual rules, ML service and policy.
//
// Usage:   npm run seed              (create transactions)
//          npm run seed -- --review  (also record a few analyst reviews)
// Needs:   backend on API_URL (default http://localhost:8080), Node 18+.
// Tip:     start the ML service first, otherwise rows get ML_UNAVAILABLE.
//
// Why two phases: velocity and "unusual amount" rules depend on each account's
// history, which the Kafka consumer builds asynchronously. So we first create
// a normal history for every account, wait, then send the suspicious transaction.

import { randomUUID } from 'node:crypto';

const API = process.env.API_URL ?? 'http://localhost:8080';
const WITH_REVIEWS = process.argv.includes('--review');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const rand = (min, max) => Math.random() * (max - min) + min;
const money = (min, max) => Math.round(rand(min, max) * 100) / 100;
const pick = (arr) => arr[Math.floor(Math.random() * arr.length)];

// Unique suffix per run so a re-run never inherits old account history
const RUN = Date.now().toString(36);

const MERCHANTS = ['m-1', 'm-2', 'm-3', 'm-4', 'm-5', 'm-6', 'm-7', 'm-8'];
const TYPES = ['PAYMENT', 'PAYMENT', 'PAYMENT', 'TRANSFER'];
const FOREIGN = ['US', 'GB', 'NG', 'RU', 'BR'];

// ---------- API helpers ----------
let consecutiveFailures = 0;

async function send(tx) {
    const body = {
        transactionType: pick(TYPES),
        currency: 'INR',
        country: 'IN',
        merchantId: pick(MERCHANTS),
        occurredAt: new Date().toISOString(),
        ...tx,
    };
    const res = await fetch(`${API}/api/transactions`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'Idempotency-Key': randomUUID() },
        body: JSON.stringify(body),
    });
    if (!res.ok) {
        consecutiveFailures++;
        const text = await res.text().catch(() => '');
        console.error(`  ! ${res.status} for ${body.accountId}: ${text}`);
        if (consecutiveFailures >= 3) {
            console.error('\nStopping after 3 failures in a row. Check the message above.');
            process.exit(1);
        }
        return null;
    }
    consecutiveFailures = 0;
    return res.json();
}

// ---------- Scenario definitions ----------
// Each scenario: how many accounts, how many baseline transactions, and a
// function that sends the "interesting" transactions (run in phase 2).
// Baselines are 3 or fewer per account so they stay under the velocity limit.
const scenarios = [
    { name: 'normal', accounts: 14, baseline: 4, finale: null },
    {
        name: 'amount-spike', accounts: 5, baseline: 3,
        finale: (acc) => [{ accountId: acc.id, deviceId: acc.device, amount: money(4000, 20000) }],
    },
    {
        name: 'moderate-spike', accounts: 5, baseline: 3,
        finale: (acc) => [{ accountId: acc.id, deviceId: acc.device, amount: money(900, 1600) }],
    },
    {
        name: 'new-device-high-amount', accounts: 5, baseline: 3,
        finale: (acc) => [{ accountId: acc.id, deviceId: `dev-new-${acc.id}`, amount: money(3000, 9000) }],
    },
    {
        name: 'country-hop', accounts: 5, baseline: 3,
        finale: (acc) => [{ accountId: acc.id, deviceId: acc.device, amount: money(200, 800), country: pick(FOREIGN) }],
    },
    {
        name: 'velocity-burst', accounts: 4, baseline: 1,
        // 7 quick small payments: trips the "more than 5 in 5 minutes" rule
        finale: (acc) =>
            Array.from({ length: 7 }, () => ({ accountId: acc.id, deviceId: acc.device, amount: money(80, 400) })),
    },
    {
        name: 'combined', accounts: 4, baseline: 3,
        finale: (acc) => [
            { accountId: acc.id, deviceId: `dev-new-${acc.id}`, amount: money(8000, 30000), country: pick(FOREIGN) },
        ],
    },
];

// ---------- Run ----------
async function main() {
    console.log(`Seeding ${API} (run ${RUN})`);

    // Fail fast if the backend is not reachable
    try {
        await fetch(`${API}/api/transactions/summary`);
    } catch {
        console.error(`Cannot reach ${API}. Is the Spring Boot backend running?`);
        process.exit(1);
    }

    // Build the account list
    const accounts = [];
    for (const s of scenarios) {
        for (let i = 1; i <= s.accounts; i++) {
            accounts.push({
                id: `acc-${s.name}-${RUN}-${i}`,
                device: `dev-${s.name}-${RUN}-${i}`,
                scenario: s,
                // each account has its own typical spend, so averages differ
                typical: money(150, 600),
            });
        }
    }

    // Phase 1: normal history, interleaved across accounts
    console.log(`Phase 1: building history for ${accounts.length} accounts…`);
    let sent = 0;
    const maxBaseline = Math.max(...scenarios.map((s) => s.baseline));
    for (let round = 0; round < maxBaseline; round++) {
        for (const acc of accounts) {
            if (round >= acc.scenario.baseline) continue;
            await send({
                accountId: acc.id,
                deviceId: acc.device,
                amount: money(acc.typical * 0.7, acc.typical * 1.3), // jitter so std dev is not zero
            });
            sent++;
            await sleep(80);
        }
    }

    // Give the Kafka consumer time to process the history before the finale
    console.log(`  ${sent} sent. Waiting 5 s for the pipeline to catch up…`);
    await sleep(5000);

    // Phase 2: the suspicious (or normal) final transactions
    console.log('Phase 2: sending the interesting transactions…');
    for (const acc of accounts) {
        if (!acc.scenario.finale) continue;
        for (const tx of acc.scenario.finale(acc)) {
            await send(tx);
            sent++;
            await sleep(velocityGap(acc.scenario.name));
        }
    }

    console.log(`Done: ${sent} transactions sent. Waiting 5 s for decisions…`);
    await sleep(5000);

    if (WITH_REVIEWS) await recordSomeReviews();
    await printSummary();
}

// Bursts must be quick to trip the velocity rule; everything else can be gentle
const velocityGap = (name) => (name === 'velocity-burst' ? 30 : 100);

// Optional: act on a few REVIEW/BLOCKED rows so the review history has content
async function recordSomeReviews() {
    console.log('Recording sample analyst reviews…');
    const res = await fetch(`${API}/api/transactions?status=BLOCKED&status=REVIEW&reviewState=UNREVIEWED&size=12`);
    if (!res.ok) return;
    const page = await res.json();
    const actions = [
        ['CONFIRM_FRAUD', 'Matches known account-takeover pattern.'],
        ['MARK_LEGITIMATE', 'Customer confirmed the purchase by phone.'],
        ['REQUEST_FURTHER_REVIEW', 'Need device history before deciding.'],
    ];
    for (const [i, t] of page.content.slice(0, 6).entries()) {
        const [action, note] = actions[i % actions.length];
        await fetch(`${API}/api/transactions/${t.id}/review`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ action, note, reviewer: 'demo-analyst' }),
        });
    }
}

async function printSummary() {
    const res = await fetch(`${API}/api/transactions/summary`);
    if (!res.ok) return;
    const s = await res.json();
    console.log('\nDatabase now has:');
    console.log(`  total ${s.total}, awaiting analyst ${s.awaitingReview}`);
    console.log('  by decision:', s.byStatus);
    console.log('  by review state:', s.byReviewState);
}

main().catch((e) => {
    console.error(e);
    process.exit(1);
});