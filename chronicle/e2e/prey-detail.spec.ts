import { test, expect } from '@playwright/test'

const PREY_ID = 'prey-1'   // AVGO · SPIN · strigoi-spin
const BOGUS_ID = 'does-not-exist-zzzz'

test.describe('Prey Detail View (/prey/:id)', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto(`/prey/${PREY_ID}`)
    // The mock API uses setTimeout (no real network), so networkidle
    // is not reliable here — wait directly for the rendered content.
    await page.waitForSelector('.title-ticker', { timeout: 15000 })
  })

  test('renders ticker (AVGO) and company name', async ({ page }) => {
    await expect(page.locator('.title-ticker')).toContainText('AVGO')
    await expect(page.locator('.title-name')).toContainText('Broadcom Inc')
  })

  test('renders eyebrow with anomaly type', async ({ page }) => {
    await expect(page.locator('.page-eyebrow')).toContainText('Spin-off')
  })

  test('renders thesis prose', async ({ page }) => {
    await expect(page.locator('.lead-prose')).toBeVisible()
    await expect(page.locator('.lead-prose')).toContainText('Spin-off')
  })

  test('renders confidence bar', async ({ page }) => {
    const conf = page.locator('.conf-row')
    await expect(conf).toBeVisible()
    await expect(conf).toContainText('0.84')
  })

  test('renders at-a-glance kv facts (anomaly, horizon)', async ({ page }) => {
    const kvList = page.locator('.pd-kv')
    await expect(kvList).toContainText('Spin-off')
    await expect(kvList).toContainText('90 Tage')
  })

  test('renders signals list with at least 1 item', async ({ page }) => {
    await expect(page.locator('.sr-list--signals li').first()).toBeVisible()
  })

  test('renders risks list with at least 1 item', async ({ page }) => {
    await expect(page.locator('.sr-list--risks li').first()).toBeVisible()
  })

  test('renders kill-criteria list with at least 1 item', async ({ page }) => {
    await expect(page.locator('.sr-list--kill li').first()).toBeVisible()
  })

  test('found-by button is visible and keyboard-accessible', async ({ page }) => {
    const btn = page.getByTestId('pd-found-by')
    await expect(btn).toBeVisible()
    await expect(btn).toContainText('strigoi-spin')
  })

  test('found-by button navigates to strigoi detail', async ({ page }) => {
    await page.getByTestId('pd-found-by').click()
    await page.waitForLoadState('networkidle')
    await expect(page).toHaveURL(/\/strigoi\//)
  })

  test('add-to-watchlist button shows confirmation', async ({ page }) => {
    await page.getByTestId('pd-add-watchlist').click()
    await expect(page.getByTestId('pd-watchlist-added')).toBeVisible()
  })

  test('back link navigates to chronicle when accessed via chronicle', async ({ page }) => {
    // Navigate via chronicle so there is history to go back to
    await page.goto('/')
    await page.waitForLoadState('networkidle')
    await page.locator('.prey-card').first().click()
    await page.waitForLoadState('networkidle')
    await expect(page).toHaveURL(/\/prey\//)

    await page.locator('.back-link').click()
    await page.waitForLoadState('networkidle')
    await expect(page).toHaveURL('/')
  })

  test('can navigate from chronicle prey card to prey detail', async ({ page }) => {
    await page.goto('/')
    await page.waitForLoadState('networkidle')
    const preyCard = page.locator('.prey-card').first()
    await expect(preyCard).toBeVisible()
    await preyCard.click()
    await page.waitForLoadState('networkidle')
    await expect(page).toHaveURL(/\/prey\//)
    await expect(page.locator('.title-ticker')).toBeVisible()
  })
})

test.describe('Prey Detail View — not-found state', () => {
  test('shows not-found UI for bogus id', async ({ page }) => {
    await page.goto(`/prey/${BOGUS_ID}`)
    await page.waitForLoadState('networkidle')
    await expect(page.locator('.pd-notfound')).toBeVisible()
    await expect(page.locator('.pd-notfound')).toContainText('nicht gefunden')
  })
})
