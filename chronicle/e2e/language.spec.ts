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

  test('switching to English localizes chronicle chrome (no leftover German)', async ({ page }) => {
    await page.click('.set-nav-item:has-text("Sprache")')
    await page.getByTestId('language-select').selectOption('en')
    await expect(page.locator('.page-title')).toHaveText('Language')

    // Navigate to the chronicle home via in-app routing (NOT a full reload —
    // the locale switch lives in the running i18n instance; a hard reload would
    // re-boot the SPA and re-fetch the default language from the mock API).
    await page.click('a.top-bar__tab:has-text("chronicle")')

    const strip = page.locator('[data-testid="dusk-strip"]')
    await expect(strip).toBeVisible()
    // English chrome present, German chrome gone.
    await expect(strip).toContainText('prey')
    await expect(strip).not.toContainText('Beute')
    await expect(strip).not.toContainText('Urteil')

    // Relative-time on a verdict card must be English ("… ago"), not German ("vor …").
    const attrib = page.locator('[data-testid="verdict-card"] .vc-attrib').first()
    await expect(attrib).toContainText('ago')
    await expect(attrib).not.toContainText('vor ')
  })
})
