import { test, expect } from '@playwright/test'

test.describe('Watchlist View (/watchlist)', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/watchlist')
    await page.waitForLoadState('networkidle')
    await expect(page.locator('[data-testid="watchlist-item"]').first()).toBeVisible()
  })

  test('renders at least 1 watchlist item', async ({ page }) => {
    expect(await page.locator('[data-testid="watchlist-item"]').count()).toBeGreaterThan(0)
  })

  test('renders all 4 filter chips', async ({ page }) => {
    await expect(page.locator('.watchlist__chip:has-text("All")')).toBeVisible()
    await expect(page.locator('.watchlist__chip:has-text("Held")')).toBeVisible()
    await expect(page.locator('.watchlist__chip:has-text("Tracking")')).toBeVisible()
    await expect(page.locator('.watchlist__chip:has-text("Alerts")')).toBeVisible()
  })

  test('"All" filter chip is active by default', async ({ page }) => {
    await expect(page.locator('.watchlist__chip--active')).toContainText('All')
  })

  test('right pane shows detail of auto-selected first item', async ({ page }) => {
    await expect(page.locator('.watchlist__right')).toBeVisible()
    await expect(page.locator('.watchlist__detail-ticker')).toBeVisible()
    await expect(page.locator('.watchlist__detail-company')).toBeVisible()
  })

  test('clicking a different item updates selection', async ({ page }) => {
    const items = page.locator('[data-testid="watchlist-item"]')
    const count = await items.count()
    test.skip(count < 2, 'need at least 2 items to test selection change')
    await items.nth(1).click()
    await expect(items.nth(1)).toHaveClass(/watchlist__item--selected/)
  })

  test('clicking "Held" filter chip filters the list', async ({ page }) => {
    await page.click('.watchlist__chip:has-text("Held")')
    await expect(page.locator('.watchlist__chip--active')).toContainText('Held')
    const hasItems = await page.locator('[data-testid="watchlist-item"]').count()
    const hasEmpty = await page.locator('.watchlist__empty').isVisible()
    expect(hasItems > 0 || hasEmpty).toBeTruthy()
  })

  test('sparkline chart is visible for selected item', async ({ page }) => {
    await expect(page.locator('.apexcharts-canvas').first()).toBeVisible()
  })
})
