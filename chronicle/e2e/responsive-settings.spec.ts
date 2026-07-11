import { test, expect } from '@playwright/test'

test.describe('Settings on mobile', () => {
  test.use({ viewport: { width: 390, height: 844 } })

  test('budget table scrolls inside its own wrapper, not the whole tab', async ({ page }) => {
    await page.goto('/settings')
    await page.waitForLoadState('networkidle')
    await page.locator('.set-nav-item', { hasText: 'Budgets & Kosten' }).click()
    await expect(page.locator('.set-budget-table')).toBeVisible()

    // The settings body itself must not pan horizontally anymore.
    const bodyOverflow = await page.locator('.settings-body').evaluate(
      el => el.scrollWidth - el.clientWidth,
    )
    expect(bodyOverflow).toBeLessThanOrEqual(0)

    // The dedicated wrapper carries the horizontal scroll instead.
    const wrapScrollable = await page
      .locator('.table-scroll:has(.set-budget-table)')
      .evaluate(el => el.scrollWidth > el.clientWidth && getComputedStyle(el).overflowX === 'auto')
    expect(wrapScrollable).toBe(true)
  })

  test('agent rows wrap; pause/edit buttons stay inside the viewport', async ({ page }) => {
    await page.goto('/settings')
    await page.waitForLoadState('networkidle')
    await page.locator('.set-nav-item', { hasText: 'Agenten' }).click()
    const row = page.locator('.agent-row').first()
    await expect(row).toBeVisible()

    const editBox = await row.locator('.agent-row__edit').boundingBox()
    expect(editBox).not.toBeNull()
    expect(editBox!.x + editBox!.width).toBeLessThanOrEqual(390 + 1)

    const bodyOverflow = await page.locator('.settings-body').evaluate(
      el => el.scrollWidth - el.clientWidth,
    )
    expect(bodyOverflow).toBeLessThanOrEqual(0)
  })
})
