import { test, expect } from '@playwright/test'

test.describe('Pattern Library View (/patterns)', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/patterns')
    await page.waitForLoadState('networkidle')
    await expect(page.locator('h1.page-title')).toBeVisible()
    await expect(page.locator('[data-testid="pending-pattern-card"]').first()).toBeVisible()
  })

  test('renders "Musterbibliothek" heading', async ({ page }) => {
    // h1 shows the page title; eyebrow shows "Musterbibliothek"
    await expect(page.locator('h1.page-title')).toContainText('Die Lehren des Woiwoden')
    await expect(page.locator('.page-eyebrow')).toContainText('Musterbibliothek')
  })

  test('renders at least 1 pending pattern card', async ({ page }) => {
    await expect(page.locator('[data-testid="pending-pattern-card"]').first()).toBeVisible()
  })

  test('renders Genehmigen, Ablehnen, Zurückstellen buttons on first pending card', async ({ page }) => {
    const firstCard = page.locator('[data-testid="pending-pattern-card"]').first()
    await expect(firstCard.locator('button:has-text("Genehmigen")')).toBeVisible()
    await expect(firstCard.locator('button:has-text("Ablehnen")')).toBeVisible()
    await expect(firstCard.locator('button:has-text("Zurückstellen")')).toBeVisible()
  })

  test('renders at least 1 active pattern row', async ({ page }) => {
    await expect(page.locator('[data-testid="active-pattern-row"]').first()).toBeVisible()
  })

  test('clicking Genehmigen removes the card from pending list', async ({ page }) => {
    const cards = page.locator('[data-testid="pending-pattern-card"]')
    const countBefore = await cards.count()
    await cards.first().locator('button:has-text("Genehmigen")').click()
    await expect(cards).toHaveCount(countBefore - 1, { timeout: 3000 })
  })

  test('clicking Ablehnen removes the card from pending list', async ({ page }) => {
    const cards = page.locator('[data-testid="pending-pattern-card"]')
    const countBefore = await cards.count()
    await cards.last().locator('button:has-text("Ablehnen")').click()
    await expect(cards).toHaveCount(countBefore - 1, { timeout: 3000 })
  })

  test('expanding active pattern reveals Deaktivieren button', async ({ page }) => {
    const firstRow = page.locator('[data-testid="active-pattern-row"]').first()
    await firstRow.locator('[data-testid="active-pattern-expand"]').click()
    await expect(firstRow.locator('button:has-text("Deaktivieren")')).toBeVisible()
  })

  test('active rows show a statement excerpt as title, slug as tooltip', async ({ page }) => {
    const name = page.locator('[data-testid="active-pattern-row"] .patterns__active-name').first()
    await expect(name).toContainText('Technology-sector spin-offs')
    await expect(name).toHaveAttribute('title', 'tech-spinoffs-outperform-industrials')
  })

  test('expand button exposes aria-expanded and rotates the chevron', async ({ page }) => {
    const row = page.locator('[data-testid="active-pattern-row"]').first()
    const btn = row.locator('.patterns__expand-btn')
    await expect(btn).toHaveAttribute('aria-expanded', 'false')
    await btn.click()
    await expect(btn).toHaveAttribute('aria-expanded', 'true')
    await expect(btn.locator('i.ph-caret-right')).toHaveClass(/patterns__chevron--open/)
  })

  test('clicking supporting-cases link opens the cases dialog with rows', async ({ page }) => {
    const firstCard = page.locator('[data-testid="pending-pattern-card"]').first()
    await firstCard.locator('button.pt-cases').click()

    const dialog = page.locator('[data-testid="pattern-cases-dialog"]')
    await expect(dialog).toBeVisible()

    const rows = dialog.locator('[data-testid="pattern-case-row"]')
    await expect(rows.first()).toBeVisible()
    expect(await rows.count()).toBeGreaterThanOrEqual(1)

    // an outcome cell renders (supported or refuted)
    await expect(
      dialog.locator('.pc-outcome').first()
    ).toBeVisible()

    // closing works
    await dialog.locator('.pc-close').click()
    await expect(dialog).not.toBeVisible()
  })
})
