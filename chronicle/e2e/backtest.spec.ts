import { test, expect } from '@playwright/test'

test.describe('Backtest View (/backtest)', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/backtest')
    await page.waitForLoadState('networkidle')
    await expect(page.locator('.backtest__header h1')).toBeVisible()
  })

  test('renders "Backtest" heading', async ({ page }) => {
    await expect(page.locator('.backtest__header h1')).toContainText('Backtest')
  })

  test('renders config panel with strigoi chips', async ({ page }) => {
    await expect(page.locator('.backtest__chips')).toBeVisible()
    await expect(page.locator('.backtest__chip').first()).toBeVisible()
  })

  test('renders date-range preset buttons', async ({ page }) => {
    await expect(page.locator('.backtest__preset').first()).toBeVisible()
  })

  test('renders universe radio options', async ({ page }) => {
    await expect(page.locator('.backtest__radio').first()).toBeVisible()
  })

  test('renders at least 3 recent backtest cards', async ({ page }) => {
    await expect.poll(() => page.locator('.backtest__run-card').count()).toBeGreaterThanOrEqual(3)
  })

  test('Overview tab is active by default', async ({ page }) => {
    await expect(page.locator('.backtest__tab--active')).toContainText('Overview')
  })

  test('renders 4 stat cards in Overview tab', async ({ page }) => {
    await expect(page.locator('.backtest__stat-card')).toHaveCount(4)
  })

  test('clicking Trades tab shows trades table', async ({ page }) => {
    await page.click('.backtest__tab:has-text("Trades")')
    await expect(page.locator('.backtest__tab--active')).toContainText('Trades')
    await expect(page.locator('.backtest__trades table')).toBeVisible()
  })

  test('clicking Equity Curve tab shows chart', async ({ page }) => {
    await page.click('.backtest__tab:has-text("Equity Curve")')
    await expect(page.locator('.backtest__tab--active')).toContainText('Equity Curve')
    await expect(page.locator('.backtest__chart .apexcharts-canvas')).toBeVisible()
  })

  test('clicking Comparison tab shows comparison table', async ({ page }) => {
    await page.click('.backtest__tab:has-text("Comparison")')
    await expect(page.locator('.backtest__comparison table')).toBeVisible()
  })

  test('clicking a different backtest card updates active state', async ({ page }) => {
    const cards = page.locator('.backtest__run-card')
    await cards.nth(1).click()
    await expect(cards.nth(1)).toHaveClass(/backtest__run-card--active/)
  })
})
