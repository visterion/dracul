import { test, expect } from '@playwright/test'

test.describe('Watchlist Compare (/watchlist)', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/watchlist')
    await page.waitForLoadState('networkidle')
    await expect(page.locator('[data-testid="watchlist-item"]').first()).toBeVisible()
  })

  test('compare toggle switches to the compare view', async ({ page }) => {
    await page.getByTestId('wl-mode-compare').click()
    await expect(page.getByTestId('watchlist-compare')).toBeVisible()
  })

  test('owner picker offers the other user', async ({ page }) => {
    await page.getByTestId('wl-mode-compare').click()
    await expect(page.getByTestId('wl-compare-with')).toBeVisible()
    await expect(page.getByTestId('wl-compare-with')).toContainText('daniel@dracul.local')
  })

  test('shared ticker lands in the BOTH bucket', async ({ page }) => {
    await page.getByTestId('wl-mode-compare').click()
    await expect(page.getByTestId('compare-both-MELI')).toBeVisible()
  })

  test('my-only ticker lands in the ONLY-ME bucket', async ({ page }) => {
    await page.getByTestId('wl-mode-compare').click()
    await expect(page.getByTestId('compare-mine-CRM')).toBeVisible()
    await expect(page.getByTestId('compare-mine-MELI')).toHaveCount(0)
  })

  test('their-only ticker lands in the ONLY-THEIRS bucket', async ({ page }) => {
    await page.getByTestId('wl-mode-compare').click()
    await expect(page.getByTestId('compare-theirs-MSFT')).toBeVisible()
  })

  test('switching back to list restores the master/detail grid', async ({ page }) => {
    await page.getByTestId('wl-mode-compare').click()
    await page.getByTestId('wl-mode-list').click()
    await expect(page.getByTestId('watchlist-list')).toBeVisible()
    await expect(page.getByTestId('watchlist-compare')).toHaveCount(0)
  })
})
