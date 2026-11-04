# Frontend (React + TypeScript + Vite)

The analyst dashboard: login, transaction list and filters, detail with the decision explanation, reviews, and the Model feedback card.

```bash
npm install
npm run dev            # http://localhost:5173 (expects the backend on :8080)
npm test               # Vitest + Testing Library + MSW (no backend needed)
npm run test:e2e       # Playwright against the real running stack (load .env first)
npm run seed           # sends demo transactions (needs INGEST_API_KEY, SEED_USERNAME, SEED_PASSWORD from ../.env)
```

In Docker the app is served by nginx, which proxies `/api` to the backend, so no CORS is needed.
The login token is kept in `sessionStorage` (see the limitations in [../docs/observability.md](../docs/observability.md#limitations)).
