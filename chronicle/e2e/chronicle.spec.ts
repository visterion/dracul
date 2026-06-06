import { test, expect } from '@playwright/test'

test.describe('Chronicle View (/)', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/')
    await page.waitForLoadState('networkidle')
    await expect(page.locator('.chronicle__banner')).toBeVisible()
  })

  test('renders morning banner with counts', async ({ page }) => {
    const banner = page.locator('.chronicle__banner')
    await expect(banner).toContainText('neue Beute')
    await expect(banner).toContainText('Urteil')
  })

  test('renders at least 1 verdict card', async ({ page }) => {
    await expect(page.locator('[data-testid="verdict-card"]').first()).toBeVisible()
  })

  test('renders at least 3 prey cards', async ({ page }) => {
    await expect.poll(() => page.locator('[data-testid="prey-card"]').count()).toBeGreaterThan(2)
  })

  test('status bar is visible', async ({ page }) => {
    await expect(page.locator('.status-bar')).toBeVisible()
  })

  test('clicking verdict card link navigates to verdict detail', async ({ page }) => {
    await page.locator('[data-testid="verdict-card"] .verdict-card__link').first().click()
    await page.waitForLoadState('networkidle')
    await expect(page).toHaveURL(/\/verdict\//)
  })

  // Note: PreyCard is a display-only component with no router-link.
  // Navigation to verdict detail is tested via verdict card links above.

  test('AVGO verdict card is visible', async ({ page }) => {
    await expect(page.locator('[data-testid="verdict-card"]:has-text("AVGO")')).toBeVisible()
  })
})
