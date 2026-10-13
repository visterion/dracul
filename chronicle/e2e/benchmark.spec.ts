import { test, expect } from '@playwright/test'

test.describe('Benchmark view (/benchmark)', () => {
  test('renders eyebrow, countdown, one row per mock strigoi and the depot block', async ({ page }) => {
    await page.goto('/benchmark')
    await page.waitForLoadState('networkidle')
    await expect(page.locator('.page-eyebrow')).toContainText('Benchmark')
    await expect(page.getByTestId('benchmark-countdown')).toBeVisible()
    await expect(page.getByTestId('benchmark-row')).toHaveCount(4)
    await expect(page.getByTestId('benchmark-depot')).toBeVisible()
  })

  test('the retired /backtest route redirects to /benchmark', async ({ page }) => {
    await page.goto('/backtest')
    await expect(page).toHaveURL('/benchmark')
  })
})

test.describe('Benchmark view at phone width', () => {
  test.use({ viewport: { width: 390, height: 844 } })

  test('the page itself never scrolls horizontally (tables scroll inside .table-scroll)', async ({ page }) => {
    await page.goto('/benchmark')
    await page.waitForLoadState('networkidle')
    await expect(page.getByTestId('benchmark-table')).toBeVisible()
    const overflow = await page.evaluate(
      () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
    )
    expect(overflow).toBeLessThanOrEqual(0)
  })

  test('the mobile bottom nav carries the benchmark tab', async ({ page }) => {
    await page.goto('/benchmark')
    await page.waitForLoadState('networkidle')
    const tab = page.locator('[data-testid="bottom-nav"] a', { hasText: 'benchmark' })
    await expect(tab).toHaveCount(1)
    await expect(tab).toHaveClass(/bottom-nav__tab--active/)
  })
})
