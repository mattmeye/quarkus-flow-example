import { expect, Page, test } from '@playwright/test';

/**
 * Drives a complete approval workflow through the Angular UI and verifies
 * that each persistent state transition is reflected in real-time via the
 * WebSocket events stream — no curl, no API stubbing, this is the same
 * thing a user would do in the browser.
 *
 * The backend runs on :8080 (started by CI before this job; locally
 * `task dev:backend` in another terminal). The Angular dev server on
 * :4200 is started by Playwright's `webServer`.
 */

const uniqueSubject = () => `E2E run ${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;

async function submitRequest(page: Page, subject: string) {
  await page.goto('/');
  await expect(page.getByRole('heading', { name: 'Requests' })).toBeVisible();

  // Form fields are simple inputs without explicit labels; use placeholders.
  await page.getByPlaceholder('Alice Schmidt').fill('Playwright Tester');
  await page.getByPlaceholder('alice@example.com').fill('e2e@example.com');
  await page.getByPlaceholder('Production deployment of payments-v2').fill(subject);
  // The terms checkbox is the only unlabeled checkbox in the form.
  await page.locator('input[type="checkbox"]').check();
  await page.getByRole('button', { name: 'Submit request' }).click();

  // The app navigates to the detail page on submit success.
  await page.waitForURL(/\/requests\/[0-9a-f-]+/);
}

async function awaitState(page: Page, label: string) {
  // The header shows the human-readable state label as a badge.
  await expect(page.locator('span.badge', { hasText: label })).toBeVisible({ timeout: 10_000 });
}

async function confirmEmail(page: Page) {
  await expect(page.getByRole('heading', { name: 'Confirm email & accept terms' })).toBeVisible();
  await page.getByPlaceholder('your.name@example.com').fill('e2e@example.com');
  // Inside the confirmation panel only one terms checkbox is rendered.
  await page.locator('.terms input[type="checkbox"]').check();
  await page.getByRole('button', { name: /Confirm/ }).click();
}

async function approveAs(page: Page, actor: string) {
  await page.getByPlaceholder('your.name@example.com').fill(actor);
  await page.getByRole('button', { name: 'Approve' }).click();
}

test.describe('Approval workflow', () => {

  test('drives a request from submission to APPROVED via the UI', async ({ page }) => {
    const subject = uniqueSubject();

    await test.step('1) submit', async () => {
      await submitRequest(page, subject);
      await awaitState(page, 'Awaiting confirmation');
    });

    await test.step('2) confirm email + terms', async () => {
      await confirmEmail(page);
      await awaitState(page, 'Awaiting Group 1');
    });

    await test.step('3) approve at Group 1', async () => {
      await approveAs(page, 'g1@example.com');
      await awaitState(page, 'Awaiting Group 2');
    });

    await test.step('4) approve at Group 2', async () => {
      await approveAs(page, 'g2@example.com');
      await awaitState(page, 'Approved');
    });

    // History panel reflects the final outcome.
    await expect(page.getByText(/Approved by both approval groups/)).toBeVisible();
  });

  test('rejecting at Group 1 sends the request to REJECTED', async ({ page }) => {
    const subject = uniqueSubject();

    await submitRequest(page, subject);
    await awaitState(page, 'Awaiting confirmation');
    await confirmEmail(page);
    await awaitState(page, 'Awaiting Group 1');

    await page.getByPlaceholder('your.name@example.com').fill('g1-no@example.com');
    await page.getByRole('button', { name: 'Reject' }).click();

    await awaitState(page, 'Rejected');
    await expect(page.getByText(/Rejected by approval group 1/)).toBeVisible();
  });
});
