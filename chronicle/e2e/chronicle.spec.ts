import { test, expect } from '@playwright/test'

test.describe('Chronicle View (/)', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/')
    await page.waitForLoadState('networkidle')
    await expect(page.locator('[data-testid="dusk-strip"]')).toBeVisible()
  })

  test('renders dusk strip with the night tally', async ({ page }) => {
    const strip = page.locator('[data-testid="dusk-strip"]')
    await expect(strip).toContainText('neue Beute')
    await expect(strip).toContainText('Urteil')
  })

  test('renders at least 1 verdict card with a consensus ring', async ({ page }) => {
    const card = page.locator('[data-testid="verdict-card"]').first()
    await expect(card).toBeVisible()
    await expect(card.locator('.consensus-ring')).toBeVisible()
  })

  test('renders at least 3 prey cards', async ({ page }) => {
    await expect.poll(() => page.locator('[data-testid="prey-card"]').count()).toBeGreaterThan(2)
  })

  test('status bar is visible', async ({ page }) => {
    await expect(page.locator('.status-bar')).toBeVisible()
  })

  test('clicking verdict card navigates to verdict detail', async ({ page }) => {
    await page.locator('[data-testid="verdict-card-read"]').first().click()
    await page.waitForLoadState('networkidle')
    await expect(page).toHaveURL(/\/verdict\//)
  })

  test('clicking a prey card navigates to prey detail', async ({ page }) => {
    await page.locator('[data-testid="prey-card"]').first().click()
    await page.waitForLoadState('networkidle')
    await expect(page).toHaveURL(/\/prey\//)
  })

  test('filter chips narrow the visible prey list', async ({ page }) => {
    const before = await page.locator('[data-testid="prey-card"]').count()
    await page.locator('.filter-chip:has-text("hohe Konfidenz")').click()
    await expect.poll(() => page.locator('[data-testid="prey-card"]').count()).toBeLessThanOrEqual(before)
  })

  test('AVGO verdict card is visible', async ({ page }) => {
    await expect(page.locator('[data-testid="verdict-card"]:has-text("AVGO")')).toBeVisible()
  })
})
