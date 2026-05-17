import { test, expect } from '@playwright/test'

const STRIGOI_NAME = 'strigoi-spin'

test.describe('Strigoi Detail View (/strigoi/:name)', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto(`/strigoi/${STRIGOI_NAME}`)
    await page.waitForLoadState('networkidle')
    await expect(page.locator('.sd__name')).toBeVisible()
  })

  test('renders agent name in header', async ({ page }) => {
    await expect(page.locator('.sd__name')).toContainText(STRIGOI_NAME)
  })

  test('renders state pill', async ({ page }) => {
    await expect(page.locator('.sd__state-pill')).toBeVisible()
  })

  test('renders 3 stat cards', async ({ page }) => {
    expect(await page.locator('.sd__stat-card').count()).toBe(3)
  })

  test('renders at least 1 run in the timeline', async ({ page }) => {
    await expect(page.locator('.sd__run').first()).toBeVisible()
  })

  test('renders at least 1 prey card in recent prey grid', async ({ page }) => {
    await expect(page.locator('[data-testid="prey-card"]').first()).toBeVisible()
  })

  test('renders ApexCharts chart container', async ({ page }) => {
    await expect(page.locator('.apexcharts-canvas').first()).toBeVisible()
  })

  test('clicking a run header expands the trace', async ({ page }) => {
    const runHeaders = page.locator('.sd__run-header')
    const traces = page.locator('.sd__run-trace')
    // The first run is auto-expanded on mount. Collapse it first, then expand again.
    await expect(traces.first()).toBeVisible()
    await runHeaders.first().click()
    await expect(traces.first()).not.toBeVisible()
    await runHeaders.first().click()
    await expect(traces.first()).toBeVisible()
  })

  test('clicking an expanded run header collapses the trace', async ({ page }) => {
    const firstHeader = page.locator('.sd__run-header').first()
    const firstTrace = page.locator('.sd__run-trace').first()
    // First run is auto-expanded on mount
    await expect(firstTrace).toBeVisible()
    await firstHeader.click()
    await expect(firstTrace).not.toBeVisible()
  })

  test('unknown strigoi name shows not-found state', async ({ page }) => {
    await page.goto('/strigoi/does-not-exist')
    await page.waitForLoadState('networkidle')
    await expect(page.locator('.sd-notfound')).toBeVisible()
  })
})
