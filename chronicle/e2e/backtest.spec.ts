import { test, expect } from '@playwright/test'

test.describe('Backtest View (/backtest)', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/backtest')
    await page.waitForLoadState('networkidle')
    await expect(page.locator('.page-title')).toBeVisible()
  })

  test('renders "Backtest" eyebrow + title', async ({ page }) => {
    await expect(page.locator('.page-eyebrow')).toContainText('Backtest')
    await expect(page.locator('.page-title')).toBeVisible()
  })

  test('renders config console with strigoi select-chips', async ({ page }) => {
    await expect(page.locator('.bt-config')).toBeVisible()
    await expect(page.locator('.select-chip').first()).toBeVisible()
  })

  test('selecting a strigoi chip marks it active', async ({ page }) => {
    // first strigoi field row chip = "spin" (active by default); pick "insider"
    const insider = page.locator('.select-chip', { hasText: 'insider' })
    await insider.click()
    await expect(insider).toHaveClass(/active/)
  })

  test('renders date-range preset buttons', async ({ page }) => {
    await expect(page.locator('.bt-preset').first()).toBeVisible()
  })

  test('renders universe radio options', async ({ page }) => {
    await expect(page.locator('.radio-opt').first()).toBeVisible()
  })

  test('selecting a universe radio marks it active', async ({ page }) => {
    const opt = page.locator('.radio-opt', { hasText: 'NASDAQ 100' })
    await opt.click()
    await expect(opt).toHaveClass(/active/)
  })

  test('renders at least 3 recent backtest cards', async ({ page }) => {
    await expect.poll(() => page.locator('.bt-recent-card').count()).toBeGreaterThanOrEqual(3)
  })

  test('Übersicht tab is active by default and shows the equity chart', async ({ page }) => {
    await expect(page.locator('.restab.active')).toContainText('Übersicht')
    await expect(page.locator('.chart-card .svg-chart')).toBeVisible()
  })

  test('clicking Trades tab shows trades table', async ({ page }) => {
    await page.click('.restab:has-text("Trades")')
    await expect(page.locator('.restab.active')).toContainText('Trades')
    await expect(page.locator('table.dt')).toBeVisible()
    await expect(page.locator('table.dt tbody tr').first()).toBeVisible()
  })

  test('clicking Equity-Kurve tab shows SVG chart', async ({ page }) => {
    await page.click('.restab:has-text("Equity-Kurve")')
    await expect(page.locator('.restab.active')).toContainText('Equity-Kurve')
    await expect(page.locator('.chart-card .svg-chart')).toBeVisible()
  })

  test('clicking Vergleich tab shows the chart card', async ({ page }) => {
    await page.click('.restab:has-text("Vergleich")')
    await expect(page.locator('.restab.active')).toContainText('Vergleich')
    await expect(page.locator('.chart-card .svg-chart')).toBeVisible()
  })

  test('switching from chart to Trades changes content', async ({ page }) => {
    await expect(page.locator('.chart-card')).toBeVisible()
    await page.click('.restab:has-text("Trades")')
    await expect(page.locator('.chart-card')).toHaveCount(0)
    await expect(page.locator('table.dt')).toBeVisible()
  })
})
