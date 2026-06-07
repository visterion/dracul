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

  test('bottom nav lists all 5 destinations', async ({ page }) => {
    await expect(page.locator('.bottom-nav__tab')).toHaveCount(5)
  })

  test('bottom nav does not contain a vistierie destination', async ({ page }) => {
    await expect(
      page.locator('.bottom-nav__tab', { hasText: 'vistierie' }),
    ).toHaveCount(0)
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

test.describe('Watchlist drill-in (mobile)', () => {
  test('row opens full-screen detail, back returns to list', async ({ page }) => {
    await page.goto('/watchlist')
    await page.waitForLoadState('networkidle')
    // list visible, detail hidden initially
    await expect(page.getByTestId('watchlist-list')).toBeVisible()
    await expect(page.getByTestId('watchlist-detail')).toBeHidden()
    // tap first row → detail panel appears
    await page.locator('[data-testid="watchlist-item"]').first().click()
    await expect(page.getByTestId('watchlist-detail')).toBeVisible()
    // back → list again
    await page.getByTestId('watchlist-back').click()
    await expect(page.getByTestId('watchlist-list')).toBeVisible()
    await expect(page.getByTestId('watchlist-detail')).toBeHidden()
  })
})
