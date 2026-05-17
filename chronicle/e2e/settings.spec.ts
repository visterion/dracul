import { test, expect } from '@playwright/test'

test.describe('Settings View (/settings)', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/settings')
    await page.waitForLoadState('networkidle')
    await expect(page.locator('.settings__nav')).toBeVisible()
  })

  test('renders sidebar nav with at least 4 nav items', async ({ page }) => {
    await expect.poll(() => page.locator('.settings__nav-item').count()).toBeGreaterThanOrEqual(4)
  })

  test('"LLM Providers" section is active by default', async ({ page }) => {
    await expect(page.locator('.settings__nav-item--active')).toContainText('LLM Providers')
  })

  test('renders at least 1 LLM provider card', async ({ page }) => {
    await expect(page.locator('.settings__provider-card').first()).toBeVisible()
  })

  test('renders Anthropic provider', async ({ page }) => {
    await expect(page.locator('.settings__provider-name:has-text("Anthropic")')).toBeVisible()
  })

  test('clicking Budgets nav item loads budget section', async ({ page }) => {
    await page.click('.settings__nav-item:has-text("Budget")')
    await expect(page.locator('.settings__nav-item--active')).toContainText('Budget')
    await expect(page.locator('.settings__budget-input').first()).toBeVisible({ timeout: 3000 })
  })

  test('budget section renders at least 4 tenant cap inputs', async ({ page }) => {
    await page.click('.settings__nav-item:has-text("Budget")')
    await expect(page.locator('.settings__budget-input').first()).toBeVisible({ timeout: 3000 })
    await expect.poll(() => page.locator('.settings__budget-grid .settings__budget-input').count()).toBeGreaterThanOrEqual(4)
  })

  test('budget section renders per-agent table with strigoi-spin', async ({ page }) => {
    await page.click('.settings__nav-item:has-text("Budget")')
    await expect(page.locator('.settings__budget-input').first()).toBeVisible({ timeout: 3000 })
    await expect(page.locator('.settings__budget-agent:has-text("strigoi-spin")').first()).toBeVisible()
  })
})
