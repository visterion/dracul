import { test, expect } from '@playwright/test'

test.describe('Executor Calibration card (/portfolio)', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/portfolio')
    await page.waitForLoadState('networkidle')
    await expect(page.getByTestId('calibration-card')).toBeVisible()
  })

  test('renders the calibration card container', async ({ page }) => {
    await expect(page.getByTestId('calibration-card')).toBeVisible()
  })

  test('renders executor Brier score', async ({ page }) => {
    const card = page.getByTestId('calibration-card')
    await expect(card).toContainText('0.180')
  })

  test('renders per-hunter table with a sufficient row', async ({ page }) => {
    const row = page.locator('[data-testid="calibration-hunter-row"][data-agent="strigoi-echo"]')
    await expect(row).toBeVisible()
    await expect(row).toContainText('0.210')
  })

  test('insufficient hunter row renders a muted chip instead of a score', async ({ page }) => {
    const row = page.locator('[data-testid="calibration-hunter-row"][data-agent="strigoi-lazarus"]')
    await expect(row).toBeVisible()
    await expect(row).toContainText('unzureichende Daten (n=8 < 30)')
  })

  test('renders veto precision rows', async ({ page }) => {
    const row = page.locator('[data-testid="veto-precision-row"][data-reason="PACE_LIMIT"]')
    await expect(row).toBeVisible()
    await expect(row).toContainText('25.0%')
  })

  test('renders caveats footnote list', async ({ page }) => {
    await expect(page.getByTestId('calibration-caveats')).toBeVisible()
    await expect(page.getByTestId('calibration-caveats')).toContainText('optimistic')
  })

  test('renders behavior stat tiles (latency, whipsaw, slippage)', async ({ page }) => {
    const card = page.getByTestId('calibration-card')
    await expect(card).toContainText('3s')
    await expect(card).toContainText('2s')
    await expect(card).toContainText('-0.02')
    await expect(card).toContainText('-0.15')
  })
})
