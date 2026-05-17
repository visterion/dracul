import { test, expect } from '@playwright/test'

test.describe('Pattern Library View (/patterns)', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/patterns')
    await page.waitForLoadState('networkidle')
    await expect(page.locator('h1.patterns__title')).toBeVisible()
    await expect(page.locator('[data-testid="pending-pattern-card"]').first()).toBeVisible()
  })

  test('renders "Pattern Library" heading', async ({ page }) => {
    await expect(page.locator('h1.patterns__title')).toContainText('Pattern Library')
  })

  test('renders at least 1 pending pattern card', async ({ page }) => {
    expect(await page.locator('[data-testid="pending-pattern-card"]').count()).toBeGreaterThan(0)
  })

  test('renders Approve, Reject, Defer buttons on first pending card', async ({ page }) => {
    const firstCard = page.locator('[data-testid="pending-pattern-card"]').first()
    await expect(firstCard.locator('button:has-text("Approve")')).toBeVisible()
    await expect(firstCard.locator('button:has-text("Reject")')).toBeVisible()
    await expect(firstCard.locator('button:has-text("Defer")')).toBeVisible()
  })

  test('renders at least 1 active pattern row', async ({ page }) => {
    expect(await page.locator('[data-testid="active-pattern-row"]').count()).toBeGreaterThan(0)
  })

  test('clicking Approve removes the card from pending list', async ({ page }) => {
    const cards = page.locator('[data-testid="pending-pattern-card"]')
    const countBefore = await cards.count()
    await cards.first().locator('button:has-text("Approve")').click()
    await expect(cards).toHaveCount(countBefore - 1, { timeout: 3000 })
  })

  test('clicking Reject removes the card from pending list', async ({ page }) => {
    const cards = page.locator('[data-testid="pending-pattern-card"]')
    const countBefore = await cards.count()
    await cards.last().locator('button:has-text("Reject")').click()
    await expect(cards).toHaveCount(countBefore - 1, { timeout: 3000 })
  })

  test('expanding active pattern reveals Deactivate button', async ({ page }) => {
    const firstRow = page.locator('[data-testid="active-pattern-row"]').first()
    await firstRow.locator('[data-testid="active-pattern-expand"]').click()
    await expect(firstRow.locator('button:has-text("Deactivate")')).toBeVisible()
  })
})
