import { test, expect } from '@playwright/test'

const SEED_TEXT = 'E2E seed:'

test.describe('Pattern review flows (Ausstehende Überprüfung)', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/patterns')
    await page.waitForLoadState('networkidle')
    await expect(page.locator('.section-head', { hasText: 'Ausstehende Überprüfung' })).toBeVisible()
  })

  test('seeded pending pattern is listed for review', async ({ page }) => {
    await expect(
      page.locator('[data-testid="pending-pattern-card"]', { hasText: SEED_TEXT })
    ).toBeVisible()
  })

  test('approve flow removes the seeded card from the pending list', async ({ page }) => {
    const card = page.locator('[data-testid="pending-pattern-card"]', { hasText: SEED_TEXT })
    await expect(card).toBeVisible()
    await card.locator('button:has-text("Genehmigen")').click()
    await expect(card).toHaveCount(0, { timeout: 3000 })
  })

  test('reject flow removes the seeded card from the pending list', async ({ page }) => {
    const card = page.locator('[data-testid="pending-pattern-card"]', { hasText: SEED_TEXT })
    await expect(card).toBeVisible()
    await card.locator('button:has-text("Ablehnen")').click()
    await expect(card).toHaveCount(0, { timeout: 3000 })
    // it must not surface among the active rows either
    await expect(
      page.locator('[data-testid="active-pattern-row"]', { hasText: SEED_TEXT })
    ).toHaveCount(0)
  })
})
