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

  test('renders all 4 filter tabs', async ({ page }) => {
    await expect(page.locator('.watch-tab:has-text("Alle")')).toBeVisible()
    await expect(page.locator('.watch-tab:has-text("gehalten")')).toBeVisible()
    await expect(page.locator('.watch-tab:has-text("verfolgt")')).toBeVisible()
    await expect(page.locator('.watch-tab:has-text("Alarme")')).toBeVisible()
  })

  test('"Alle" filter tab is active by default', async ({ page }) => {
    await expect(page.locator('.watch-tab.active')).toContainText('Alle')
  })

  test('right pane shows detail of auto-selected first item', async ({ page }) => {
    await expect(page.locator('[data-testid="watchlist-detail"]')).toBeVisible()
    await expect(page.locator('.wd-ticker')).toBeVisible()
    await expect(page.locator('.wd-name')).toBeVisible()
  })

  test('clicking a different item updates selection', async ({ page }) => {
    const items = page.locator('[data-testid="watchlist-item"]')
    const count = await items.count()
    test.skip(count < 2, 'need at least 2 items to test selection change')
    await items.nth(1).click()
    await expect(items.nth(1)).toHaveClass(/active/)
  })

  test('clicking "Positionen gehalten" filter tab filters the list', async ({ page }) => {
    await page.click('.watch-tab:has-text("gehalten")')
    await expect(page.locator('.watch-tab.active')).toContainText('gehalten')
    const hasItems = await page.locator('[data-testid="watchlist-item"]').count()
    const hasEmpty = await page.locator('.watchlist__empty, .empty').isVisible()
    expect(hasItems > 0 || hasEmpty).toBeTruthy()
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
    const ticker = await firstRow.locator('.wr-ticker').textContent()
    await firstRow.locator('[data-testid^="wl-delete-"]').click()
    await expect(page.locator(`.wr-ticker:has-text("${ticker?.trim()}")`)).toHaveCount(0)
  })

  test('alert feed renders alerts for an item that has them', async ({ page }) => {
    // AVGO (first item) has alerts in the mock
    await page.locator('[data-testid="watchlist-item"]').first().click()
    await expect(page.locator('.alert-item').first()).toBeVisible()
  })

  // ── Position flow (backend-backed) ──────────────────────────────

  test('item WITHOUT a position shows the "Position erfassen" add card', async ({ page }) => {
    // CRM has entryPrice null in the mock
    await page.locator('[data-testid="watchlist-item"]:has-text("CRM")').click()
    await expect(page.locator('.wd-addpos')).toBeVisible()
    await expect(page.getByTestId('wl-add-position')).toBeVisible()
  })

  test('clicking "Position erfassen" reveals entry-price input prefilled with current price', async ({ page }) => {
    const row = page.locator('[data-testid="watchlist-item"]:has-text("CRM")')
    await row.click()
    // current price shown in the row
    const currentPx = (await row.locator('.wr-px').textContent())?.replace(/[$,\s]/g, '')
    await page.getByTestId('wl-add-position').click()
    const entryInput = page.getByTestId('wl-entry-price')
    await expect(entryInput).toBeVisible()
    const entryVal = await entryInput.inputValue()
    expect(parseFloat(entryVal)).toBeCloseTo(parseFloat(currentPx ?? '0'), 2)
  })

  test('editing share count updates the live P&L display (AVGO)', async ({ page }) => {
    // AVGO has entryPrice 1190.00, currentPrice 1247.50 → P&L positive
    await page.locator('[data-testid="watchlist-item"]:has-text("AVGO")').click()
    const pnl = page.getByTestId('wl-pnl-abs')
    await expect(pnl).toBeVisible()
    const before = (await pnl.textContent())?.trim()
    const shares = page.getByTestId('wl-share-count')
    await shares.fill('100')
    await shares.blur()
    await expect(pnl).not.toHaveText(before ?? '')
  })
})
