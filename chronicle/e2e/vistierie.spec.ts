import { test, expect } from '@playwright/test'

// Vistierie (Schatzkammer) no longer has its own route — it is embedded
// admin-only inside Settings. These tests exercise it via /settings.
test.describe('Vistierie (Schatzkammer) embedded in Settings', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/settings')
    await page.waitForLoadState('networkidle')
    // Admin sees Schatzkammer pinned and active by default.
    await expect(page.locator('.set-nav-item.active')).toContainText('Schatzkammer')
    await expect(page.locator('[data-testid="tier-budget-bar"]').first()).toBeVisible()
  })

  test('renders at least 3 tier ledger rows', async ({ page }) => {
    await expect.poll(() => page.locator('[data-testid="tier-budget-bar"]').count()).toBeGreaterThanOrEqual(3)
  })

  test('renders "Reasoning" tier from mock data', async ({ page }) => {
    await expect(page.locator('[data-testid="tier-budget-bar"]:has-text("Reasoning")')).toBeVisible()
  })

  test('renders agent spend rows with strigoi-spin first', async ({ page }) => {
    await expect(page.locator('.agent-spend')).toBeVisible()
    await expect(page.locator('.as-name').first()).toContainText('strigoi-spin')
  })

  test('renders the daily-spend chart (no ApexCharts)', async ({ page }) => {
    await expect(page.locator('.chart-card .price-chart').first()).toBeVisible()
    await expect(page.locator('.apexcharts-canvas')).toHaveCount(0)
  })

  test('renders avg/day and month-total foot stats', async ({ page }) => {
    await expect(page.locator('.vist-foot .vf-v').first()).toBeVisible()
    await expect(page.locator('.vist-foot .vf-v').nth(1)).toBeVisible()
  })
})

test.describe('Legacy /vistierie route', () => {
  test('redirects to the chronicle home', async ({ page }) => {
    await page.goto('/vistierie')
    await page.waitForLoadState('networkidle')
    await expect(page).toHaveURL(/\/$/)
  })
})
