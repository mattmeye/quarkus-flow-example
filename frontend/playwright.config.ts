import { defineConfig, devices } from '@playwright/test';

/**
 * Drives the Angular UI end-to-end against a real backend. The dev
 * server (`ng serve` on :4200) is started by Playwright itself via
 * `webServer`; the Quarkus backend on :8080 must already be running
 * (CI starts it explicitly in the e2e job; locally use `task dev:backend`
 * in a separate shell first).
 *
 * Specs live in `e2e/` and use the `app-root` page object pattern only
 * where it pays off — the app is small enough that direct selectors are
 * fine.
 */
export default defineConfig({
  testDir: './e2e',
  fullyParallel: false, // one workflow at a time keeps the backend log readable
  forbidOnly: !!process.env['CI'],
  retries: process.env['CI'] ? 2 : 0,
  workers: 1,
  reporter: process.env['CI']
    ? [['list'], ['html', { open: 'never' }], ['github']]
    : 'list',

  use: {
    baseURL: process.env['PLAYWRIGHT_BASE_URL'] ?? 'http://localhost:4200',
    trace: 'on-first-retry',
    video: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },

  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],

  webServer: process.env['PLAYWRIGHT_BASE_URL']
    ? undefined
    : {
        command: 'npm start',
        url: 'http://localhost:4200',
        reuseExistingServer: !process.env['CI'],
        timeout: 180_000,
        stdout: 'pipe',
        stderr: 'pipe',
      },
});
