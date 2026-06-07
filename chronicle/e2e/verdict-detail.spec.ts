import { test, expect } from '@playwright/test'

const VERDICT_ID = 'verdict-1'

test.describe('Verdict Detail View (/verdict/:id)', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto(`/verdict/${VERDICT_ID}`)
    await page.waitForLoadState('networkidle')
    await expect(page.locator('.title-ticker')).toBeVisible()
  })

  test('renders symbol AVGO and company name', async ({ page }) => {
    await expect(page.locator('.title-ticker')).toContainText('AVGO')
    await expect(page.locator('.title-name')).toContainText('Broadcom Inc')
  })

  test('renders eyebrow with Strigoi convergence count', async ({ page }) => {
    await expect(page.locator('.page-eyebrow')).toContainText('3')
  })

  test('renders tag pills (consensus, horizon, anomaly classes)', async ({ page }) => {
    await expect(page.locator('.verdict-tags .tag-pill').first()).toBeVisible()
    await expect(page.locator('.verdict-tags')).toContainText('0.84')
    await expect(page.locator('.verdict-tags')).toContainText('90d')
  })

  test('renders drop-cap prose paragraph', async ({ page }) => {
    await expect(page.locator('.verdict-prose p.drop').first()).toBeVisible()
  })

  test('renders consensus ring in the aside', async ({ page }) => {
    const ring = page.locator('.verdict-aside .consensus-ring')
    await expect(ring).toBeVisible()
    await expect(ring).toContainText('0.84')
  })

  test('renders "at a glance" facts including avg confidence', async ({ page }) => {
    await expect(page.locator('.kv-list')).toContainText('0.78')
  })

  test('renders signals section with at least 1 item', async ({ page }) => {
    await expect(page.locator('.vd-list--signals li').first()).toBeVisible()
  })

  test('renders risks section with at least 1 item', async ({ page }) => {
    await expect(page.locator('.vd-list--risks li').first()).toBeVisible()
  })

  test('lists contributing strigoi with confidence', async ({ page }) => {
    const first = page.getByTestId('vd-contributor').first()
    await expect(first).toBeVisible()
    await expect(first).toContainText(/\d\.\d{2}/)
  })

  test('back link navigates to /', async ({ page }) => {
    await page.locator('.back-link').click()
    await page.waitForLoadState('networkidle')
    await expect(page).toHaveURL('/')
  })

  test('contributor navigates to strigoi detail', async ({ page }) => {
    await page.getByTestId('vd-contributor').first().click()
    await page.waitForLoadState('networkidle')
    await expect(page).toHaveURL(/\/strigoi\//)
  })

  test('unknown verdict id shows not-found state', async ({ page }) => {
    await page.goto('/verdict/does-not-exist')
    await page.waitForLoadState('networkidle')
    await expect(page.locator('.vd-notfound')).toBeVisible()
  })

  test('decision badge appears after clicking Track', async ({ page }) => {
    await page.getByTestId('vd-decide-track').click()
    await expect(page.getByTestId('vd-decision-badge')).toContainText('TRACK')
  })

  test('add to watchlist shows confirmation', async ({ page }) => {
    await page.getByTestId('vd-add-watchlist').click()
    await expect(page.getByTestId('vd-watchlist-added')).toBeVisible()
  })

  test('can add a note', async ({ page }) => {
    const noteText = `playwright note ${Date.now()}`
    await page.getByTestId('vd-note-input').fill(noteText)
    await page.getByTestId('vd-note-submit').click()
    await expect(page.getByTestId('vd-notes-list')).toContainText(noteText)
  })
})
