import { test, expect } from '@playwright/test'

// ≥960px renders the full desktop top bar. At 1024px the nav previously
// pushed the controls (bell / theme toggle / logout) out of the viewport,
// where `overflow-x: hidden` clipped them away (regression guard).
test.describe('Top bar on narrow desktop', () => {
  test.use({ viewport: { width: 1024, height: 768 } })

  test('controls stay fully inside the viewport at 1024px', async ({ page }) => {
    await page.goto('/')
    await page.waitForLoadState('networkidle')
    await expect(page.locator('.top-bar__nav')).toBeVisible()

    const controls = page.locator('.top-bar__controls')
    await expect(controls).toBeVisible()
    const box = await controls.boundingBox()
    expect(box).not.toBeNull()
    expect(box!.x + box!.width).toBeLessThanOrEqual(1024 + 1)

    const bell = await page.getByTestId('live-toggle').boundingBox()
    expect(bell).not.toBeNull()
    expect(bell!.x + bell!.width).toBeLessThanOrEqual(1024 + 1)
  })

  test('active-tab underline is not clipped by the nav scroll container', async ({ page }) => {
    await page.goto('/')
    await page.waitForLoadState('networkidle')
    const clipped = await page.locator('.top-bar__nav').evaluate(
      el => el.scrollHeight > el.clientHeight,
    )
    expect(clipped).toBe(false)
  })
})
