import { test, expect } from '@playwright/test'

test.describe('Language switcher (/settings)', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/settings')
    await page.waitForLoadState('networkidle')
  })

  test('language nav item opens the language section', async ({ page }) => {
    await page.click('.set-nav-item:has-text("Sprache")')
    await expect(page.getByTestId('language-section')).toBeVisible()
    await expect(page.getByTestId('language-select')).toBeVisible()
  })

  test('switching to English changes the page title to English', async ({ page }) => {
    await page.click('.set-nav-item:has-text("Sprache")')
    await page.getByTestId('language-select').selectOption('en')
    await expect(page.locator('.page-title')).toHaveText('Language')
  })

  test('switching back to German restores the German title', async ({ page }) => {
    await page.click('.set-nav-item:has-text("Sprache")')
    await page.getByTestId('language-select').selectOption('en')
    await expect(page.locator('.page-title')).toHaveText('Language')
    await page.getByTestId('language-select').selectOption('de')
    await expect(page.locator('.page-title')).toHaveText('Sprache')
  })
})
