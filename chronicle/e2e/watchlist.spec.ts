import { test, expect } from '@playwright/test'

test.describe('Watchlist View (/watchlist)', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/watchlist')
    await page.waitForLoadState('networkidle')
    await expect(page.locator('[data-testid="watchlist-item"]').first()).toBeVisible()
  })

  test('renders at least 1 watchlist item', async ({ page }) => {
    await expect(page.locator('[data-testid="watchlist-item"]').first()).toBeVisible()
  })

  test('renders all 4 filter chips', async ({ page }) => {
    await expect(page.locator('.watchlist__chip:has-text("Alle")')).toBeVisible()
    await expect(page.locator('.watchlist__chip:has-text("gehalten")')).toBeVisible()
    await expect(page.locator('.watchlist__chip:has-text("verfolgt")')).toBeVisible()
    await expect(page.locator('.watchlist__chip:has-text("Alarme")')).toBeVisible()
  })

  test('"Alle" filter chip is active by default', async ({ page }) => {
    await expect(page.locator('.watchlist__chip--active')).toContainText('Alle')
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

  test('clicking "Positionen gehalten" filter chip filters the list', async ({ page }) => {
    await page.click('.watchlist__chip:has-text("gehalten")')
    await expect(page.locator('.watchlist__chip--active')).toContainText('gehalten')
    const hasItems = await page.locator('[data-testid="watchlist-item"]').count()
    const hasEmpty = await page.locator('.watchlist__empty').isVisible()
    expect(hasItems > 0 || hasEmpty).toBeTruthy()
  })

  test('sparkline chart is visible for selected item', async ({ page }) => {
    await expect(page.locator('.apexcharts-canvas').first()).toBeVisible()
  })

  test('can add a symbol via dialog', async ({ page }) => {
    await page.getByTestId('wl-open-add').click()
    await page.getByTestId('wl-add-symbol').fill('TSLA')
    await page.getByTestId('wl-add-submit').click()
    await expect(page.getByText('TSLA').first()).toBeVisible()
  })

  test('can toggle the tag on a row', async ({ page }) => {
    const firstRow = page.locator('[data-testid="watchlist-item"]').first()
    const toggle = firstRow.locator('[data-testid^="wl-tag-"]').first()
    const before = (await toggle.textContent())?.trim()
    await toggle.click()
    await expect(toggle).not.toHaveText(before ?? '')
  })

  test('can delete a row after confirm', async ({ page }) => {
    page.on('dialog', d => d.accept())
    const firstRow = page.locator('[data-testid="watchlist-item"]').first()
    const ticker = await firstRow.locator('.watchlist__ticker').textContent()
    await firstRow.locator('[data-testid^="wl-delete-"]').click()
    await expect(page.locator(`.watchlist__ticker:has-text("${ticker?.trim()}")`)).toHaveCount(0)
  })
})
