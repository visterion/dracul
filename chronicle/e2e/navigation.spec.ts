import { test, expect } from '@playwright/test'

test.describe('Top-bar navigation', () => {
  test('top nav has exactly 5 destinations', async ({ page }) => {
    await page.goto('/')
    await page.waitForLoadState('networkidle')
    await expect(page.locator('a.top-bar__tab')).toHaveCount(5)
  })

  test('top nav does not contain a vistierie destination', async ({ page }) => {
    await page.goto('/')
    await page.waitForLoadState('networkidle')
    await expect(page.locator('a.top-bar__tab', { hasText: 'vistierie' })).toHaveCount(0)
  })

  test('chronicles tab navigates to /', async ({ page }) => {
    await page.goto('/watchlist')
    await page.click('a.top-bar__tab:has-text("chronicle")')
    await page.waitForLoadState('networkidle')
    await expect(page).toHaveURL('/')
    await expect(page.locator('[data-testid="dusk-strip"]')).toBeVisible()
  })

  test('watchlist tab navigates to /watchlist', async ({ page }) => {
    await page.goto('/')
    await page.click('a.top-bar__tab:has-text("watchlist")')
    await page.waitForLoadState('networkidle')
    await expect(page).toHaveURL('/watchlist')
    await expect(page.locator('.watch-grid')).toBeVisible()
  })

  test('pattern library tab navigates to /patterns', async ({ page }) => {
    await page.goto('/')
    await page.click('a.top-bar__tab:has-text("pattern library")')
    await page.waitForLoadState('networkidle')
    await expect(page).toHaveURL('/patterns')
    await expect(page.locator('h1.page-title')).toBeVisible()
  })

  test('backtest tab navigates to /backtest', async ({ page }) => {
    await page.goto('/')
    await page.click('a.top-bar__tab:has-text("backtest")')
    await page.waitForLoadState('networkidle')
    await expect(page).toHaveURL('/backtest')
    await expect(page.locator('.page-eyebrow')).toContainText('Backtest')
  })

  test('settings tab navigates to /settings', async ({ page }) => {
    await page.goto('/')
    await page.click('a.top-bar__tab:has-text("settings")')
    await page.waitForLoadState('networkidle')
    await expect(page).toHaveURL('/settings')
    await expect(page.locator('.settings-nav')).toBeVisible()
  })

  test('DRACUL wordmark links to /', async ({ page }) => {
    await page.goto('/watchlist')
    await page.click('a.top-bar__wordmark')
    await page.waitForLoadState('networkidle')
    await expect(page).toHaveURL('/')
    await expect(page.locator('[data-testid="dusk-strip"]')).toBeVisible()
  })

  test('the removed /vistierie route redirects to /', async ({ page }) => {
    await page.goto('/vistierie')
    await expect(page).toHaveURL('/')
  })

  test('unknown route redirects to /', async ({ page }) => {
    await page.goto('/does-not-exist')
    await expect(page).toHaveURL('/')
  })
})
