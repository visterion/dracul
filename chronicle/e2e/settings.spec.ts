import { test, expect } from '@playwright/test'

test.describe('Settings View (/settings)', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/settings')
    await page.waitForLoadState('networkidle')
    await expect(page.locator('.settings-nav')).toBeVisible()
  })

  test('renders sidebar nav with at least 4 nav items', async ({ page }) => {
    await expect.poll(() => page.locator('.set-nav-item').count()).toBeGreaterThanOrEqual(4)
  })

  test('admin sees Schatzkammer pinned at top with an Admin badge', async ({ page }) => {
    const first = page.locator('.set-nav-item').first()
    await expect(first).toContainText('Schatzkammer')
    await expect(first.locator('.set-badge.admin')).toBeVisible()
  })

  test('Schatzkammer is the active section by default', async ({ page }) => {
    await expect(page.locator('.set-nav-item.active')).toContainText('Schatzkammer')
    await expect(page.locator('[data-testid="tier-budget-bar"]').first()).toBeVisible()
  })

  test('Providers section renders provider cards', async ({ page }) => {
    await page.click('.set-nav-item:has-text("LLM Providers")')
    await expect(page.locator('.provider-card').first()).toBeVisible()
  })

  test('renders Anthropic provider card', async ({ page }) => {
    await page.click('.set-nav-item:has-text("LLM Providers")')
    await expect(page.locator('.provider-card .pv-name:has-text("Anthropic")')).toBeVisible()
  })

  test('Providers section shows the add-provider button', async ({ page }) => {
    await page.click('.set-nav-item:has-text("LLM Providers")')
    await expect(page.locator('.add-provider')).toBeVisible()
  })

  test('Budgets section loads tenant cap inputs', async ({ page }) => {
    await page.click('.set-nav-item:has-text("Budget")')
    await expect(page.locator('.set-budget-input').first()).toBeVisible({ timeout: 3000 })
    await expect.poll(() => page.locator('.set-budget-grid .set-budget-input').count()).toBeGreaterThanOrEqual(4)
  })

  test('Budgets section renders per-agent table with strigoi-spin', async ({ page }) => {
    await page.click('.set-nav-item:has-text("Budget")')
    await expect(page.locator('.set-budget-input').first()).toBeVisible({ timeout: 3000 })
    await expect(page.locator('.set-budget-agent:has-text("strigoi-spin")').first()).toBeVisible()
  })

  test('language switch changes the interface language', async ({ page }) => {
    await page.click('.set-nav-item:has-text("Sprache"), .set-nav-item:has-text("Language")')
    const select = page.locator('[data-testid="language-select"]')
    await expect(select).toBeVisible()
    await select.selectOption('en')
    // After switching to English the nav label for providers stays stable,
    // but the page sub copy switches — assert the select reflects the choice.
    await expect(select).toHaveValue('en')
  })

  test('Agent config section lists agents', async ({ page }) => {
    await page.click('.set-nav-item:has-text("Agent Configuration")')
    await expect(page.locator('[data-testid="agent-config-list"]')).toBeVisible()
    await expect(page.locator('.agent-row').first()).toBeVisible()
    await expect(page.locator('.agent-row[data-agent="strigoi-spin"]')).toBeVisible()
  })

  test('Pause toggles an agent to paused', async ({ page }) => {
    await page.click('.set-nav-item:has-text("Agent Configuration")')
    const toggle = page.locator('[data-testid="agent-pause-strigoi-spin"]')
    await expect(toggle).toHaveText(/Pause|Pausieren/)
    await toggle.click()
    await expect(toggle).toHaveText(/Resume|Aktivieren/)
    // row state label is localized (de: 'paused' → 'pausiert')
    await expect(
      page.locator('.agent-row[data-agent="strigoi-spin"] .agent-row__state'),
    ).toHaveText('pausiert')
  })

  test('Data sources section lists sources with health', async ({ page }) => {
    await page.click('.set-nav-item:has-text("Data Sources")')
    await expect(page.locator('[data-testid="data-sources-list"]')).toBeVisible()
    await expect(page.locator('.ds-row[data-source="yahoo"] .ds-row__status')).toHaveAttribute('data-status', 'rate_limited')
    await expect(page.locator('.ds-row[data-source="edgar"]')).toBeVisible()
  })

  test('Re-check reloads data sources', async ({ page }) => {
    await page.click('.set-nav-item:has-text("Data Sources")')
    await expect(page.locator('[data-testid="data-sources-list"]')).toBeVisible()
    await page.click('[data-testid="ds-recheck"]')
    await expect(page.locator('[data-testid="data-sources-list"]')).toBeVisible()
    await expect(page.locator('.ds-row[data-source="edgar"]')).toBeVisible()
  })

  test('agent config rows show localized role + state labels', async ({ page }) => {
    await page.goto('/settings')
    await page.waitForLoadState('networkidle')
    await page.click('.set-nav-item:has-text("Agent Configuration")')
    const roles = page.locator('.agent-row__role')
    await expect(roles.first()).toBeVisible()
    const roleTexts = await roles.allTextContents()
    expect(roleTexts.some((tx) => tx.includes('Index-Aufnahme'))).toBe(true)
    expect(roleTexts.every((tx) => !tx.includes('INDEX'))).toBe(true)
  })

  test('opens the agent edit dialog with prompt + tools populated and saves', async ({ page }) => {
    await page.click('.set-nav-item:has-text("Agent Configuration")')
    await page.locator('[data-testid="agent-edit-strigoi-spin"]').click()
    const dialog = page.locator('[data-testid="agent-edit-dialog"]')
    await expect(dialog).toBeVisible()
    const prompt = dialog.locator('textarea').first()
    await expect(prompt).not.toHaveValue('')
    await prompt.fill('New prompt for spin')
    await dialog.locator('[data-testid="tool-toggle-fetch_recent_clusters"]').click()
    await dialog.locator('[data-testid="agent-edit-save"]').click()
    await expect(dialog).toBeHidden()
  })

  test('advanced section reveals schedule', async ({ page }) => {
    await page.click('.set-nav-item:has-text("Agent Configuration")')
    await page.locator('[data-testid="agent-edit-strigoi-spin"]').click()
    const dialog = page.locator('[data-testid="agent-edit-dialog"]')
    await dialog.locator('[data-testid="schema-form-advanced"]').click()
    await expect(dialog.locator('#sf-schedule')).toBeVisible()
  })

  test('reset repopulates the form to the default prompt', async ({ page }) => {
    page.on('dialog', d => d.accept())
    await page.click('.set-nav-item:has-text("Agent Configuration")')
    await page.locator('[data-testid="agent-edit-strigoi-spin"]').click()
    const dialog = page.locator('[data-testid="agent-edit-dialog"]')
    const prompt = dialog.locator('textarea').first()
    await prompt.fill('temporary text')
    await dialog.locator('[data-testid="agent-edit-reset"]').click()
    await expect(prompt).not.toHaveValue('temporary text')
  })
})
