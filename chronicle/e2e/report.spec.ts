import { test, expect } from '@playwright/test'

test.describe('Morning Report View (/report)', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/report')
    await page.waitForLoadState('networkidle')
    await expect(page.getByTestId('morning-report')).toBeVisible()
  })

  test('renders the morning-report container', async ({ page }) => {
    await expect(page.getByTestId('morning-report')).toBeVisible()
  })

  test('renders all three mock positions (BBB, CCC, AAA)', async ({ page }) => {
    await expect(page.locator('.report-symbol', { hasText: 'BBB' })).toBeVisible()
    await expect(page.locator('.report-symbol', { hasText: 'CCC' })).toBeVisible()
    await expect(page.locator('.report-symbol', { hasText: 'AAA' })).toBeVisible()
  })

  test('renders at least one order ticket', async ({ page }) => {
    await expect(page.getByTestId('order-ticket').first()).toBeVisible()
  })

  test('renders the read-only note (German locale)', async ({ page }) => {
    await expect(page.locator('.report-note')).toContainText('Nur informativ')
    await expect(page.locator('.report-note')).toContainText('Dracul platziert keine Orders')
  })

  test('SELL action pill is visible on BBB', async ({ page }) => {
    const row = page.locator('.report-row', { hasText: 'BBB' })
    await expect(row.locator('.report-action')).toContainText('SELL')
  })

  test('TRIM action pill is visible on CCC', async ({ page }) => {
    const row = page.locator('.report-row', { hasText: 'CCC' })
    await expect(row.locator('.report-action')).toContainText('TRIM')
  })

  test('HOLD action pill is visible on AAA', async ({ page }) => {
    const row = page.locator('.report-row', { hasText: 'AAA' })
    await expect(row.locator('.report-action')).toContainText('HOLD')
  })

  test('each position row has an order ticket', async ({ page }) => {
    const rows = page.locator('.report-row')
    await expect(rows).toHaveCount(3)
    for (let i = 0; i < 3; i++) {
      await expect(rows.nth(i).getByTestId('order-ticket')).toBeVisible()
    }
  })
})
