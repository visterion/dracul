import { test, expect } from '@playwright/test'

test.describe('Detail page title', () => {
  test('ticker and company name render with a visible gap', async ({ page }) => {
    await page.goto('/prey/prey-1')
    await page.waitForLoadState('networkidle')
    const ticker = await page.locator('.title-ticker').boundingBox()
    const name = await page.locator('.title-name').boundingBox()
    expect(ticker).not.toBeNull()
    expect(name).not.toBeNull()
    // Vue's whitespace:'condense' used to drop the space entirely (gap 0).
    expect(name!.x - (ticker!.x + ticker!.width)).toBeGreaterThanOrEqual(4)
  })

  test('prey detail uses the prose width once loaded', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto('/prey/prey-1')
    await page.waitForLoadState('networkidle')
    const maxW = await page.locator('article.pd').evaluate(
      el => getComputedStyle(el as HTMLElement).maxWidth,
    )
    expect(maxW).toBe('1180px')
  })
})
