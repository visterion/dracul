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

  test('renders the 2 filter tabs', async ({ page }) => {
    await expect(page.locator('.watch-tab:has-text("Alle")')).toBeVisible()
    await expect(page.locator('.watch-tab:has-text("Alarme")')).toBeVisible()
  })

  test('"Alle" filter tab is active by default', async ({ page }) => {
    await expect(page.locator('.watch-tab.active')).toContainText('Alle')
  })

  test('right pane shows detail of the auto-selected first item', async ({ page }) => {
    await expect(page.locator('[data-testid="watchlist-detail"]')).toBeVisible()
    await expect(page.locator('.wd-ticker')).toBeVisible()
    await expect(page.locator('.wd-name')).toBeVisible()
  })

  test('clicking a different item updates selection', async ({ page }) => {
    const items = page.locator('[data-testid="watchlist-item"]')
    const count = await items.count()
    test.skip(count < 2, 'need at least 2 items')
    await items.nth(1).click()
    await expect(items.nth(1)).toHaveClass(/active/)
  })

  test('can add a symbol via dialog', async ({ page }) => {
    await page.getByTestId('wl-open-add').click()
    await page.getByTestId('wl-add-symbol').fill('TSLA')
    await page.getByTestId('wl-add-submit').click()
    await expect(page.getByText('TSLA').first()).toBeVisible()
  })

  test('can delete a row after confirm', async ({ page }) => {
    page.on('dialog', d => d.accept())
    const before = await page.locator('[data-testid="watchlist-item"]').count()
    const firstRow = page.locator('[data-testid="watchlist-item"]').first()
    const deleteId = await firstRow.locator('[data-testid^="wl-delete-"]').getAttribute('data-testid')
    await firstRow.locator('[data-testid^="wl-delete-"]').click()
    await expect(page.locator(`[data-testid="${deleteId}"]`)).toHaveCount(0)
    await expect(page.locator('[data-testid="watchlist-item"]')).toHaveCount(before - 1)
  })

  test('alert feed renders alerts for a tracking item that has them (ADBE)', async ({ page }) => {
    await page.locator('[data-testid="watchlist-item"]:has-text("ADBE")').click()
    await expect(page.locator('.alert-item').first()).toBeVisible()
  })

  test('EUR-native rows hide the native original (no origPrice token)', async ({ page }) => {
    // SP-2: MoneyDisplay hides the native parenthetical when native currency ==
    // display currency. Every tracking row in the watchlist list is EUR-native
    // displayed in EUR (the lone USD-native item, AVGO, is a position and lives
    // in /portfolio, not in this list). App boots in German -> token 'urspr.'.
    // NVDA (wl-2) is EUR-native -> native line hidden in its row.
    const nvdaRow = page.locator('[data-testid="watchlist-item"]:has-text("NVDA")').first()
    await expect(nvdaRow).toBeVisible()
    await expect(nvdaRow.locator('.wr-price .money')).toBeVisible()
    await expect(nvdaRow.locator('.wr-price').getByText('urspr.', { exact: false })).toHaveCount(0)

    // No row in the list should surface the native parenthetical at all.
    await expect(page.locator('.watch-rows .wr-price').getByText('urspr.', { exact: false })).toHaveCount(0)
  })

  test('foreign rows are grouped under an owner separator, rows carry no email', async ({ page }) => {
    const sep = page.getByTestId('wl-owner-daniel@dracul.local')
    await expect(sep).toBeVisible()
    await expect(sep).toContainText('daniel@dracul.local')
    await expect(page.locator('.watch-row .wr-owner')).toHaveCount(0)
  })

  test('foreign rows are read-only (no delete control)', async ({ page }) => {
    const foreign = page.locator('.watch-row[data-owner="daniel@dracul.local"]').first()
    await expect(foreign).toBeVisible()
    await expect(foreign.locator('[data-testid^="wl-delete-"]')).toHaveCount(0)
  })

  test('typing an unknown ticker shows an add CTA that opens the dialog prefilled', async ({ page }) => {
    await page.locator('.watch-search input').fill('TSLA')
    const cta = page.getByTestId('wl-search-add')
    await expect(cta).toBeVisible()
    await cta.click()
    await expect(page.getByTestId('wl-add-symbol')).toHaveValue('TSLA')
  })

  test('typing a non-ticker search term shows no add CTA', async ({ page }) => {
    await page.locator('.watch-search input').fill('nonexistent company')
    await expect(page.locator('.watch-list-pane .em-text')).toBeVisible()
    await expect(page.getByTestId('wl-search-add')).toHaveCount(0)
  })

  test('a ticker hidden by the active filter shows no add CTA', async ({ page }) => {
    await page.click('.watch-tab:has-text("Alarme")')
    await page.locator('.watch-search input').fill('CRM')
    await expect(page.getByTestId('wl-search-add')).toHaveCount(0)
  })

  test('add button enables for a digit-leading exchange symbol', async ({ page }) => {
    await page.getByTestId('wl-open-add').click()
    await page.getByTestId('wl-add-symbol').fill('3750.HK')
    await expect(page.getByTestId('wl-add-submit')).toBeEnabled()
  })

  test('pressing Enter in the add dialog submits and shows a success toast', async ({ page }) => {
    await page.getByTestId('wl-open-add').click()
    await page.getByTestId('wl-add-symbol').fill('TSLA')
    await page.getByTestId('wl-add-symbol').press('Enter')
    await expect(page.getByTestId('app-toast')).toBeVisible()
    await expect(page.locator('[data-testid="watchlist-item"]:has-text("TSLA")')).toBeVisible()
  })

  test('a row whose name equals its symbol renders the symbol only once', async ({ page }) => {
    await page.getByTestId('wl-open-add').click()
    await page.getByTestId('wl-add-symbol').fill('PYPL')
    await page.getByTestId('wl-add-symbol').press('Enter')
    const row = page.locator('[data-testid="watchlist-item"]:has-text("PYPL")').first()
    await expect(row).toBeVisible()
    await expect(row.locator('.wr-name')).toHaveCount(0)
  })
})
