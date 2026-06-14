import { test, expect } from '@playwright/test'

test.describe('Portfolio View (/portfolio)', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/portfolio')
    await page.waitForLoadState('networkidle')
    await expect(page.locator('[data-testid="portfolio-row"]').first()).toBeVisible()
  })

  test('renders position rows', async ({ page }) => {
    await expect(page.locator('[data-testid="portfolio-row"]').first()).toBeVisible()
  })

  test('shows an exit-signal action badge on a position', async ({ page }) => {
    await expect(page.locator('[data-testid="portfolio-row"][data-symbol="NVDA"]')).toContainText('SELL')
  })

  test('clicking a position with a signal opens the exit detail with rationale', async ({ page }) => {
    await page.locator('[data-testid="portfolio-row"][data-symbol="NVDA"]').click()
    await expect(page).toHaveURL(/\/exit-signal\//)
    await expect(page.locator('.ed-rationale')).toBeVisible()
    await expect(page.locator('.ed-rationale')).not.toHaveText('')
  })

  test('can add a position via the dialog', async ({ page }) => {
    await page.getByTestId('pf-open-add').click()
    await page.getByTestId('pf-symbol').fill('TSLA')
    await page.getByTestId('pf-entry').fill('200')
    await page.getByTestId('pf-size').fill('10')
    await page.getByTestId('pf-submit').click()
    await expect(page.locator('[data-testid="portfolio-row"][data-symbol="TSLA"]')).toBeVisible()
  })

  test('can clear a position after confirm', async ({ page }) => {
    page.on('dialog', d => d.accept())
    const row = page.locator('[data-testid="portfolio-row"][data-symbol="AVGO"]')
    await expect(row).toBeVisible()
    await row.getByTestId('pf-delete-AVGO').click()
    await expect(page.locator('[data-testid="portfolio-row"][data-symbol="AVGO"]')).toHaveCount(0)
  })
})
