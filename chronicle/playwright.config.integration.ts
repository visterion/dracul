/// <reference types="node" />
import { defineConfig, devices } from '@playwright/test'

const BASE_URL = process.env.TEST_BASE_URL ?? 'http://localhost:8080'

export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  retries: 1,
  reporter: 'list',
  use: {
    baseURL: BASE_URL,
    trace: 'on-first-retry',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
})
