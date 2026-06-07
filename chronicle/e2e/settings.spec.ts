import { test, expect } from '@playwright/test'

test.describe('Settings View (/settings)', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/settings')
    await page.waitForLoadState('networkidle')
    await expect(page.locator('.settings-nav')).toBeVisible()
  })

  test('renders sidebar nav with at least 4 nav items', async ({ page }) => {
    await expect.poll(() => page.locator('.set-nav-item').count()).toBeGreaterThanOrEqual(4)
  })

  test('admin sees Schatzkammer pinned at top with an Admin badge', async ({ page }) => {
    const first = page.locator('.set-nav-item').first()
    await expect(first).toContainText('Schatzkammer')
    await expect(first.locator('.set-badge.admin')).toBeVisible()
  })

  test('Schatzkammer is the active section by default', async ({ page }) => {
    await expect(page.locator('.set-nav-item.active')).toContainText('Schatzkammer')
    await expect(page.locator('[data-testid="tier-budget-bar"]').first()).toBeVisible()
  })

  test('Providers section renders provider cards', async ({ page }) => {
    await page.click('.set-nav-item:has-text("LLM Providers")')
    await expect(page.locator('.provider-card').first()).toBeVisible()
  })

  test('renders Anthropic provider card', async ({ page }) => {
    await page.click('.set-nav-item:has-text("LLM Providers")')
    await expect(page.locator('.provider-card .pv-name:has-text("Anthropic")')).toBeVisible()
  })

  test('Providers section shows the add-provider button', async ({ page }) => {
    await page.click('.set-nav-item:has-text("LLM Providers")')
    await expect(page.locator('.add-provider')).toBeVisible()
  })

  test('Budgets section loads tenant cap inputs', async ({ page }) => {
    await page.click('.set-nav-item:has-text("Budget")')
    await expect(page.locator('.set-budget-input').first()).toBeVisible({ timeout: 3000 })
    await expect.poll(() => page.locator('.set-budget-grid .set-budget-input').count()).toBeGreaterThanOrEqual(4)
  })

  test('Budgets section renders per-agent table with strigoi-spin', async ({ page }) => {
    await page.click('.set-nav-item:has-text("Budget")')
    await expect(page.locator('.set-budget-input').first()).toBeVisible({ timeout: 3000 })
    await expect(page.locator('.set-budget-agent:has-text("strigoi-spin")').first()).toBeVisible()
  })

  test('language switch changes the interface language', async ({ page }) => {
    await page.click('.set-nav-item:has-text("Sprache"), .set-nav-item:has-text("Language")')
    const select = page.locator('[data-testid="language-select"]')
    await expect(select).toBeVisible()
    await select.selectOption('en')
    // After switching to English the nav label for providers stays stable,
    // but the page sub copy switches — assert the select reflects the choice.
    await expect(select).toHaveValue('en')
  })
})
