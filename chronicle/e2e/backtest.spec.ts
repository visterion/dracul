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

  test('overview tab is active by default and shows the equity chart', async ({ page }) => {
    await expect(page.locator('[data-testid="restab-overview"]')).toHaveAttribute('aria-selected', 'true')
    await expect(page.locator('.chart-card .price-chart')).toBeVisible()
  })

  test('clicking trades tab shows trades table', async ({ page }) => {
    await page.click('[data-testid="restab-trades"]')
    await expect(page.locator('[data-testid="restab-trades"]')).toHaveAttribute('aria-selected', 'true')
    await expect(page.locator('table.dt')).toBeVisible()
    await expect(page.locator('table.dt tbody tr').first()).toBeVisible()
  })

  test('clicking equity tab shows equity chart', async ({ page }) => {
    await page.click('[data-testid="restab-equity"]')
    await expect(page.locator('[data-testid="restab-equity"]')).toHaveAttribute('aria-selected', 'true')
    await expect(page.locator('.chart-card .price-chart')).toBeVisible()
  })

  test('clicking compare tab shows the chart card', async ({ page }) => {
    await page.click('[data-testid="restab-compare"]')
    await expect(page.locator('[data-testid="restab-compare"]')).toHaveAttribute('aria-selected', 'true')
    await expect(page.locator('.chart-card .price-chart')).toBeVisible()
  })

  test('switching from chart to trades changes content', async ({ page }) => {
    await expect(page.locator('.chart-card')).toBeVisible()
    await page.click('[data-testid="restab-trades"]')
    await expect(page.locator('.chart-card')).toHaveCount(0)
    await expect(page.locator('table.dt')).toBeVisible()
  })

  test('results carry a context header from the selected run', async ({ page }) => {
    await expect(page.getByTestId('bt-context')).toContainText('Strigoi-Spin · Russell 2000 · 2024–2026')
    await page.locator('.bt-recent-card', { hasText: 'Strigoi-Echo' }).click()
    await expect(page.getByTestId('bt-context')).toContainText('Strigoi-Echo · S&P 500 · 2023–2026')
  })

  test('keyboard activation of recent cards (Enter) updates context', async ({ page }) => {
    // The second card contains 'Strigoi-Echo'; focus and press Enter
    const secondCard = page.locator('.bt-recent-card').nth(1)
    await secondCard.focus()
    await page.keyboard.press('Enter')
    // Verify the context updates to show Strigoi-Echo
    await expect(page.getByTestId('bt-context')).toContainText('Strigoi-Echo · S&P 500 · 2023–2026')
  })

  test('keyboard activation of recent cards (Space) updates context', async ({ page }) => {
    // The second card contains 'Strigoi-Echo'; focus and press Space
    const secondCard = page.locator('.bt-recent-card').nth(1)
    await secondCard.focus()
    await page.keyboard.press('Space')
    // Verify the context updates to show Strigoi-Echo
    await expect(page.getByTestId('bt-context')).toContainText('Strigoi-Echo · S&P 500 · 2023–2026')
  })
})
