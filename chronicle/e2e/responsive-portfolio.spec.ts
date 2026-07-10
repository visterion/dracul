import { test, expect } from '@playwright/test'

test.describe('Portfolio on mobile', () => {
  test.use({ viewport: { width: 390, height: 844 } })

  test('page gutter present and row actions inside the viewport', async ({ page }) => {
    await page.goto('/portfolio')
    await page.waitForLoadState('networkidle')

    // Gutter: the view root must use content-inner (mobile padding = --space-4).
    const padLeft = await page.locator('.portfolio-view').evaluate(
      el => parseFloat(getComputedStyle(el as HTMLElement).paddingLeft),
    )
    expect(padLeft).toBeGreaterThanOrEqual(12)

    // "+ Position" fully inside the viewport.
    const add = await page.getByTestId('pf-open-add').boundingBox()
    expect(add).not.toBeNull()
    expect(add!.x + add!.width).toBeLessThanOrEqual(390 + 1)

    // First row's edit button reachable without horizontal panning.
    const edit = page.locator('[data-testid^="pf-edit-"]').first()
    await expect(edit).toBeVisible()
    const editBox = await edit.boundingBox()
    expect(editBox).not.toBeNull()
    expect(editBox!.x + editBox!.width).toBeLessThanOrEqual(390 + 1)

    // No horizontal document overflow.
    const overflow = await page.evaluate(
      () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
    )
    expect(overflow).toBeLessThanOrEqual(0)
  })

  test('desktop keeps the single-line row layout', async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 900 })
    await page.goto('/portfolio')
    await page.waitForLoadState('networkidle')
    const wrap = await page.locator('.pf-row').first().evaluate(
      el => getComputedStyle(el as HTMLElement).flexWrap,
    )
    expect(wrap).toBe('nowrap')
  })
})
