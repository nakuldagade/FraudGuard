// Takes the screenshots used in the README and docs/demo-script.md into docs/images/.
// Needs the stack running with demo data (make demo) and .env loaded:
//   set -a; source .env; set +a; cd frontend && node scripts/take-screenshots.mjs
import { chromium } from '@playwright/test';

const DASH = process.env.DASHBOARD_URL ?? 'http://localhost:8081';
const GRAFANA = process.env.GRAFANA_URL ?? 'http://localhost:3000';
const API = process.env.API_URL ?? 'http://localhost:8080';
const OUT = new URL('../../docs/images/', import.meta.url).pathname;

const browser = await chromium.launch();
const ctx = await browser.newContext({ viewport: { width: 1500, height: 950 } });
const page = await ctx.newPage();

// 1. Dashboard
await page.goto(DASH);
await page.screenshot({ path: `${OUT}login.png` });
await page.getByLabel('Username').fill(process.env.FRAUDGUARD_ANALYST_USERNAME);
await page.getByLabel('Password').fill(process.env.FRAUDGUARD_ANALYST_PASSWORD);
await page.getByRole('button', { name: 'Sign in' }).click();
await page.getByRole('combobox').nth(0).selectOption('REVIEW');
await page.locator('.table-wrap tbody tr').first().waitFor();
await page.waitForTimeout(800);
await page.locator('.table-wrap tbody tr').first().click();
await page.getByText('Reviewing as').waitFor();
await page.waitForTimeout(500);
await page.screenshot({ path: `${OUT}dashboard.png`, fullPage: true });

// 2. Grafana (anonymous login is off, so sign in)
const g = await ctx.newPage();
await g.goto(`${GRAFANA}/login`);
await g.getByLabel(/email or username/i).fill('admin');
await g.getByLabel(/password/i).first().fill(process.env.GRAFANA_ADMIN_PASSWORD ?? 'admin');
await g.getByRole('button', { name: /log in/i }).click();
await g.waitForTimeout(1500);
await g.setViewportSize({ width: 1500, height: 2300 });
await g.goto(`${GRAFANA}/d/fraudguard-overview/fraudguard-overview?orgId=1&from=now-30m&to=now&kiosk`);
await g.waitForTimeout(6000);
await g.screenshot({ path: `${OUT}grafana.png` });

// 3. Swagger UI
const s = await ctx.newPage();
await s.goto(`${API}/swagger-ui/index.html`);
await s.waitForSelector('.opblock-tag');
await s.screenshot({ path: `${OUT}api-docs.png` });

await browser.close();
console.log('saved to', OUT);
