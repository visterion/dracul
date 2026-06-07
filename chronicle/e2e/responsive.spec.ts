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

  test('mobile shell hides the status bar but keeps the live bell', async ({ page }) => {
    // Live-alert bell stays visible on mobile (top-bar controls are kept).
    await expect(page.getByTestId('live-toggle')).toBeVisible()
    // Status bar is rendered via v-if="!smAndDown", so it is absent from the
    // DOM on mobile — assert it is not present at all.
    await expect(page.locator('.status-bar')).toHaveCount(0)
  })

  test('tapping a bottom-nav tab navigates', async ({ page }) => {
    // The bottom-nav label renders lowercase ("watchlist"); no text-transform.
    await page.locator('.bottom-nav__tab', { hasText: 'watchlist' }).click()
    await expect(page).toHaveURL(/watchlist/)
  })
})
