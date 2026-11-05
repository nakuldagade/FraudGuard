import { defineConfig } from '@playwright/test';

// Smoke test against the REAL stack (backend, Kafka, Postgres, Redis, ML service, seeded data).
// Start the stack and load .env first (see docs/testing.md), then:  npm run test:e2e
export default defineConfig({
  testDir: './e2e',
  testMatch: '**/*.e2e.ts',
  timeout: 60_000,
  retries: 0,
  reporter: [['list']],
  use: { baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:5173', trace: 'retain-on-failure' },
  // Default: start the Vite dev server (it talks to a backend running on your machine, which allows its origin).
  // Against the Docker stack set E2E_BASE_URL=http://localhost:8081 (nginx serves the page and proxies /api): no dev server is started.
  webServer: process.env.E2E_BASE_URL
    ? undefined
    : { command: 'npm run dev -- --port 5173 --strictPort', url: 'http://localhost:5173', reuseExistingServer: true },
});
