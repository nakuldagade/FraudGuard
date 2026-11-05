import { expect, test } from '@playwright/test';

const USER = process.env.FRAUDGUARD_ANALYST_USERNAME;
const PASS = process.env.FRAUDGUARD_ANALYST_PASSWORD;

// One happy path through the real system: log in, filter, open a row, submit a review.
// Needs data in the database: run `npm run seed` first.
test('analyst can log in, filter, open a transaction and submit a review', async ({ page }) => {
  test.skip(!USER || !PASS, 'set FRAUDGUARD_ANALYST_USERNAME / _PASSWORD (load .env)');

  await page.goto('/');
  await expect(page.getByRole('button', { name: 'Sign in', exact: true })).toBeVisible();

  // wrong password first: stays on the login page
  await page.getByLabel('Username').fill(USER!);
  await page.getByLabel('Password').fill('definitely-wrong');
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  await expect(page.getByText('Invalid username or password')).toBeVisible();

  await page.getByLabel('Password').fill(PASS!);
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  await expect(page.locator('.user-chip').getByText(USER!, { exact: true })).toBeVisible();

  // filter to transactions waiting for an analyst
  await page.getByRole('combobox').nth(0).selectOption('REVIEW');
  // wait for the list filtered by BOTH choices, so we never click a row that is about to be replaced
  const filtered = page.waitForResponse((r) => r.url().includes('status=REVIEW') && r.url().includes('reviewState=UNREVIEWED'));
  await page.getByRole('combobox').nth(1).selectOption('UNREVIEWED');
  await filtered;
  const rows = page.locator('.table-wrap tbody tr');
  await expect(rows.first()).toBeVisible();

  // open the first row; the detail panel shows who is reviewing
  await rows.first().click();
  await expect(page.getByText('Reviewing as')).toBeVisible();
  await expect(page.getByRole('dialog').locator('strong', { hasText: USER! })).toBeVisible();

  // submit a review and see it in the history
  await page.getByPlaceholder(/Note/).fill('e2e smoke test');
  await page.getByRole('button', { name: 'Request further review' }).click();
  await expect(page.locator('.review-list').getByText('e2e smoke test')).toBeVisible();
  await expect(page.locator('.review-list').getByText(`by ${USER}`).first()).toBeVisible();
});

const ADMIN = process.env.FRAUDGUARD_ADMIN_USERNAME;
const ADMIN_PASS = process.env.FRAUDGUARD_ADMIN_PASSWORD;

// The admin role sees an Operations tab with live system status; an analyst does not (and the API says 403 either way).
test('admin gets the Operations tab, analyst does not', async ({ page, browser }) => {
  test.skip(!ADMIN || !ADMIN_PASS || !USER || !PASS, 'set the admin and analyst credentials (load .env)');

  await page.goto('/');
  await page.getByLabel('Username').fill(ADMIN!);
  await page.getByLabel('Password').fill(ADMIN_PASS!);
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  await page.getByRole('button', { name: 'Operations' }).click();
  const ops = page.getByRole('region', { name: 'Operations' });
  await expect(ops.getByText('Database')).toBeVisible();
  await expect(ops.getByText('UP').first()).toBeVisible();
  await expect(ops.getByRole('button', { name: 'Re-drive stuck transactions' })).toBeVisible();

  // a second, separate browser session as an analyst
  const analystPage = await (await browser.newContext()).newPage();
  await analystPage.goto('/');
  await analystPage.getByLabel('Username').fill(USER!);
  await analystPage.getByLabel('Password').fill(PASS!);
  await analystPage.getByRole('button', { name: 'Sign in', exact: true }).click();
  await expect(analystPage.locator('.user-chip').getByText(USER!, { exact: true })).toBeVisible();
  await expect(analystPage.getByRole('button', { name: 'Operations' })).toHaveCount(0);
});
