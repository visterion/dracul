import { test, expect } from '@playwright/test'

const VERDICT_ID = 'verdict-1'

test.describe('Verdict Detail View (/verdict/:id)', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto(`/verdict/${VERDICT_ID}`)
    await page.waitForLoadState('networkidle')
    await expect(page.locator('.vd__symbol')).toBeVisible()
  })

  test('renders symbol AVGO', async ({ page }) => {
    await expect(page.locator('.vd__symbol')).toContainText('AVGO')
  })

  test('renders company name Broadcom Inc', async ({ page }) => {
    await expect(page.locator('.vd__company')).toContainText('Broadcom Inc')
  })

  test('renders breadcrumb with chronicle link', async ({ page }) => {
    await expect(page.locator('.vd__bc-link').first()).toContainText('chronicle')
  })

  test('renders at least 1 anomaly type badge', async ({ page }) => {
    await expect(page.locator('.vd__badge').first()).toBeVisible()
  })

  test('renders signals section with at least 1 item', async ({ page }) => {
    await expect(page.locator('.vd__list--signals li').first()).toBeVisible()
  })

  test('renders risks section with at least 1 item', async ({ page }) => {
    await expect(page.locator('.vd__list--risks li').first()).toBeVisible()
  })

  test('renders at least 1 contributing strigoi card', async ({ page }) => {
    await expect(page.locator('.vd__contributor').first()).toBeVisible()
  })

  test('breadcrumb chronicle link navigates to /', async ({ page }) => {
    await page.locator('.vd__bc-link').first().click()
    await page.waitForLoadState('networkidle')
    await expect(page).toHaveURL('/')
  })

  test('contributing strigoi name navigates to strigoi detail', async ({ page }) => {
    await page.locator('.vd__contributor-name').first().click()
    await page.waitForLoadState('networkidle')
    await expect(page).toHaveURL(/\/strigoi\//)
  })

  test('unknown verdict id shows not-found state', async ({ page }) => {
    await page.goto('/verdict/does-not-exist')
    await page.waitForLoadState('networkidle')
    await expect(page.locator('.vd-notfound')).toBeVisible()
  })
})
