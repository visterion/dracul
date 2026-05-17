import { test, expect } from '@playwright/test'

test.describe('Vistierie View (/vistierie)', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/vistierie')
    await page.waitForLoadState('networkidle')
    await expect(page.locator('[data-testid="tier-budget-bar"]').first()).toBeVisible()
  })

  test('renders "Vistierie" heading', async ({ page }) => {
    await expect(page.locator('.vistierie__header h1')).toContainText('Vistierie')
  })

  test('renders at least 3 tier budget bars', async ({ page }) => {
    await expect.poll(() => page.locator('[data-testid="tier-budget-bar"]').count()).toBeGreaterThanOrEqual(3)
  })

  test('renders "Reasoning" tier from mock data', async ({ page }) => {
    await expect(page.locator('[data-testid="tier-budget-bar"]:has-text("Reasoning")')).toBeVisible()
  })

  test('renders agent spending bars', async ({ page }) => {
    await expect(page.locator('.vistierie__agent-bars')).toBeVisible()
    await expect(page.locator('.vistierie__agent-row').first()).toBeVisible()
  })

  test('renders strigoi-spin as first agent', async ({ page }) => {
    await expect(page.locator('.vistierie__agent-label').first()).toContainText('strigoi-spin')
  })

  test('renders ApexCharts area chart', async ({ page }) => {
    await expect(page.locator('.apexcharts-canvas').first()).toBeVisible()
  })

  test('renders Avg/day stat', async ({ page }) => {
    await expect(page.locator('.vistierie__stat-label:has-text("Avg/day")')).toBeVisible()
  })

  test('renders Month total stat', async ({ page }) => {
    await expect(page.locator('.vistierie__stat-label:has-text("Month total")')).toBeVisible()
  })
})
