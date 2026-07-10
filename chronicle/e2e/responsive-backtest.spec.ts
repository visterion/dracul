import { test, expect } from '@playwright/test'

// Project convention (global.css): the only mobile breakpoint is 959.98px.
// The backtest form previously kept its 110px label column between 600–959px.
test.describe('Backtest form at tablet width', () => {
  test.use({ viewport: { width: 800, height: 900 } })

  test('bt-field collapses to a single column below 960px', async ({ page }) => {
    await page.goto('/backtest')
    await page.waitForLoadState('networkidle')
    const gtc = await page.locator('.bt-field').first().evaluate(
      el => getComputedStyle(el as HTMLElement).gridTemplateColumns,
    )
    expect(gtc.trim().split(/\s+/)).toHaveLength(1)
  })
})
