import { test, expect } from '@playwright/test'

test.describe('Exit-signal detail on mobile', () => {
  test.use({ viewport: { width: 390, height: 844 } })

  test('gutter present, header timestamp inside the viewport, no overflow', async ({ page }) => {
    await page.goto('/exit-signal/es-1')
    await page.waitForLoadState('networkidle')
    await expect(page.locator('.ed-head')).toBeVisible()

    const padLeft = await page.locator('.exit-detail').evaluate(
      el => parseFloat(getComputedStyle(el as HTMLElement).paddingLeft),
    )
    expect(padLeft).toBeGreaterThanOrEqual(12)

    const runat = await page.locator('.ed-runat').boundingBox()
    expect(runat).not.toBeNull()
    expect(runat!.x + runat!.width).toBeLessThanOrEqual(390 + 1)

    const overflow = await page.evaluate(
      () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
    )
    expect(overflow).toBeLessThanOrEqual(0)
  })
})
