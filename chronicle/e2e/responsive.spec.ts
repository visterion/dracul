import { test, expect } from '@playwright/test'

// Phones + portrait tablets (< 960px) get the mobile shell.
test.use({ viewport: { width: 390, height: 844 } })

test.describe('Responsive shell (mobile viewport)', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/')
    await page.waitForLoadState('networkidle')
  })

  test('bottom nav is shown and the desktop top-bar nav is hidden', async ({ page }) => {
    await expect(page.getByTestId('bottom-nav')).toBeVisible()
    await expect(page.locator('.top-bar__nav')).toBeHidden()
  })

  test('bottom nav lists all 6 destinations', async ({ page }) => {
    await expect(page.locator('.bottom-nav__tab')).toHaveCount(6)
  })

  test('bottom nav is horizontally scrollable (content wider than viewport)', async ({ page }) => {
    const scroll = page.locator('.bottom-nav__scroll')
    const overflow = await scroll.evaluate(
      (el) => el.scrollWidth > el.clientWidth + 1,
    )
    expect(overflow).toBe(true)
  })

  test('tapping a bottom-nav tab navigates', async ({ page }) => {
    await page.locator('.bottom-nav__tab', { hasText: 'Watchlist' }).click()
    await expect(page).toHaveURL(/watchlist/)
  })
})
