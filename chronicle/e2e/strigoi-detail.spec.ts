import { test, expect } from '@playwright/test'

const STRIGOI_NAME = 'strigoi-spin'

test.describe('Strigoi Detail View (/strigoi/:name)', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto(`/strigoi/${STRIGOI_NAME}`)
    await page.waitForLoadState('networkidle')
    await expect(page.locator('.page-title')).toBeVisible()
  })

  test('renders agent name in header', async ({ page }) => {
    await expect(page.locator('.page-title')).toContainText(STRIGOI_NAME)
  })

  test('renders state dot + label in the sub line', async ({ page }) => {
    await expect(page.locator('.strigoi-state .state-dot')).toBeVisible()
    await expect(page.locator('.strigoi-state')).not.toBeEmpty()
  })

  test('renders 4 stat tiles', async ({ page }) => {
    await expect(page.locator('.stat-grid .stat-tile')).toHaveCount(4)
  })

  test('renders the run trace', async ({ page }) => {
    await expect(page.locator('[data-testid="run-trace"]')).toBeVisible()
    await expect(page.locator('[data-testid="run-trace"] .trace-line').first()).toBeVisible()
  })

  test('renders at least 1 prey card in the recent prey feed', async ({ page }) => {
    await expect(page.locator('.feed [data-testid="prey-card"]').first()).toBeVisible()
  })

  test('renders the configuration aside', async ({ page }) => {
    await expect(page.locator('.verdict-aside .kv-list')).toBeVisible()
    await expect(page.locator('.verdict-aside .kv-row').first()).toBeVisible()
  })

  test('clicking a prey card navigates to the prey detail view', async ({ page }) => {
    await page.locator('.feed [data-testid="prey-card"]').first().click()
    await expect(page).toHaveURL(/\/prey\//)
  })

  test('unknown strigoi name shows not-found state', async ({ page }) => {
    await page.goto('/strigoi/does-not-exist')
    await page.waitForLoadState('networkidle')
    await expect(page.locator('.sd-notfound')).toBeVisible()
  })

  test('config panel shows a humanized recurring schedule', async ({ page }) => {
    await page.goto('/strigoi/strigoi-spin')
    await page.waitForLoadState('networkidle')
    await expect(page.locator('.kv-v', { hasText: /werktags|weekdays/ }).first()).toBeVisible()
  })
})
