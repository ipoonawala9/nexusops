import { defineConfig, devices } from '@playwright/test'

/**
 * Journeys run against the real stack: `make e2e` starts Docker Compose (UI :3000, API, Postgres, Redis, Mailpit)
 * and sets E2E_BASE_URL. Serial: the journeys share rate-limit buckets (one client IP behind nginx) and Mailpit.
 */
export default defineConfig({
  testDir: './e2e',
  globalSetup: './e2e/global-setup.ts',
  fullyParallel: false,
  workers: 1,
  timeout: 90_000,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? 'github' : 'list',
  use: { baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:3000', trace: 'on-first-retry' },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
})
