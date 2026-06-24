import { test, expect } from '@playwright/test'

const SSE_BODY =
  'event: alert.new\n' +
  'data: {"symbol":"TST","trigger_type":"PRICE_SPIKE","severity":"CRITICAL","thesis":"e2e injected alert","ts":"2026-06-04T12:00:00Z"}\n\n'

test.describe('Live alert panel (SSE)', () => {
  // The live store only connects in non-mock mode. Under the standard config
  // (mock dev server on :5173) it never connects, so skip there; this runs under
  // the integration config (real non-mock build).
  test.beforeEach(({ baseURL }) => {
    test.skip(!baseURL || baseURL.includes('5173'),
      'live-alerts runs under the integration config (non-mock build + backend)')
  })

  test('streamed alert.new appears in the panel', async ({ page }) => {
    await page.route('**/api/events', route =>
      route.fulfill({
        status: 200,
        contentType: 'text/event-stream',
        headers: { 'Cache-Control': 'no-cache' },
        body: SSE_BODY,
      }),
    )

    await page.goto('/')
    await page.locator('[data-testid="live-toggle"]').click()

    const panel = page.locator('[data-testid="live-alert-panel"]')
    await expect(panel).toBeVisible()

    const item = page.locator('[data-testid="live-alert-item"]').first()
    await expect(item).toContainText('TST')
    await expect(item).toContainText('Kritisch')
    await expect(item).toContainText('Kurssprung')
    await expect(item).not.toContainText('CRITICAL')
    await expect(item).not.toContainText('PRICE_SPIKE')
  })

  test('streamed STOP_BREACHED alert renders the localized label', async ({ page }) => {
    const body =
      'event: alert.new\n' +
      'data: {"symbol":"AAA","trigger_type":"STOP_BREACHED","severity":"CRITICAL","thesis":"e2e injected stop alert","ts":"2026-06-24T14:30:00Z"}\n\n'
    await page.route('**/api/events', route =>
      route.fulfill({
        status: 200,
        contentType: 'text/event-stream',
        headers: { 'Cache-Control': 'no-cache' },
        body,
      }),
    )

    await page.goto('/')
    await page.locator('[data-testid="live-toggle"]').click()
    await expect(page.locator('[data-testid="live-alert-panel"]')).toBeVisible()

    const item = page.locator('[data-testid="live-alert-item"]').first()
    await expect(item).toContainText('AAA')
    await expect(item).toContainText('Stop gerissen')
    await expect(item).not.toContainText('STOP_BREACHED')
  })
})
