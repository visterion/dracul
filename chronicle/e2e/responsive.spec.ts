import { test, expect } from '@playwright/test'

// Phones + portrait tablets (< 960px) get the mobile shell.
test.use({ viewport: { width: 390, height: 844 } })

test.describe('Responsive shell (mobile viewport)', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/')
    await page.waitForLoadState('networkidle')
  })

  test('bottom nav is shown and the desktop top-bar nav is hidden', async ({ page }) => {
    await expect(page.getByTestId('bottom-nav')).toBeVisible()
    await expect(page.locator('.top-bar__nav')).toBeHidden()
  })

  test('bottom nav lists all 8 destinations', async ({ page }) => {
    await expect(page.locator('.bottom-nav__tab')).toHaveCount(8)
  })

  test('bottom nav does not contain a vistierie destination', async ({ page }) => {
    await expect(
      page.locator('.bottom-nav__tab', { hasText: 'vistierie' }),
    ).toHaveCount(0)
  })

  test('mobile shell hides the status bar but keeps the live bell', async ({ page }) => {
    // Live-alert bell stays visible on mobile (top-bar controls are kept).
    await expect(page.getByTestId('live-toggle')).toBeVisible()
    // Status bar is rendered via v-if="!smAndDown", so it is absent from the
    // DOM on mobile — assert it is not present at all.
    await expect(page.locator('.status-bar')).toHaveCount(0)
  })

  test('tapping a bottom-nav tab navigates', async ({ page }) => {
    // The bottom-nav label renders lowercase ("watchlist"); no text-transform.
    await page.locator('.bottom-nav__tab', { hasText: 'watchlist' }).click()
    await expect(page).toHaveURL(/watchlist/)
  })
})

test.describe('Mobile hardening (chat2 fixes)', () => {
  // Number of explicit tracks in a computed `grid-template-columns` value,
  // e.g. "390px" → 1, "201.6px 188px" → 2.
  const trackCount = (gtc: string) =>
    gtc === 'none' ? 0 : gtc.trim().split(/\s+/).length

  test('verdict card collapses to a single column at 390px', async ({ page }) => {
    await page.goto('/')
    await page.waitForLoadState('networkidle')
    const card = page.getByTestId('verdict-card').first()
    await expect(card).toBeVisible()
    const gtc = await card.evaluate(
      el => getComputedStyle(el as HTMLElement).gridTemplateColumns,
    )
    expect(trackCount(gtc)).toBe(1)
  })

  for (const width of [360, 390]) {
    test(`no horizontal overflow on Chronicle at ${width}px`, async ({ page }) => {
      await page.setViewportSize({ width, height: 844 })
      await page.goto('/')
      await page.waitForLoadState('networkidle')
      const overflow = await page.evaluate(() => {
        const d = document.documentElement
        return d.scrollWidth - d.clientWidth
      })
      expect(overflow).toBeLessThanOrEqual(0)
    })
  }

  test('prey card with a long unbreakable token does not overflow (regression)', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 })
    await page.goto('/')
    await page.waitForLoadState('networkidle')
    // Model-generated prey content can contain long unbreakable tokens (company
    // names, tickers, URLs). They must wrap, not expand the card off-screen.
    const result = await page.evaluate(() => {
      const card = document.querySelector('[data-testid="prey-card"]') as HTMLElement | null
      if (!card) return { skipped: true, overflow: 0, cardRight: 0, vw: 0 }
      const LONG = 'WolverineWorldwideInternationalHoldingsAcquisitionCorporationXYZ1234567890'
      card.querySelectorAll('.prey-thesis, .sr-list li, .prey-name').forEach((el) => {
        el.textContent = LONG
      })
      void card.offsetWidth
      const vw = document.documentElement.clientWidth
      return { skipped: false, overflow: document.documentElement.scrollWidth - vw, cardRight: card.getBoundingClientRect().right, vw }
    })
    expect(result.skipped, 'a prey card should be present on the chronicle home').toBe(false)
    expect(result.overflow).toBeLessThanOrEqual(0)
    expect(result.cardRight).toBeLessThanOrEqual(result.vw + 1)
  })

  test('verdict detail has no horizontal overflow at 360px', async ({ page }) => {
    await page.setViewportSize({ width: 360, height: 844 })
    await page.goto('/')
    await page.waitForLoadState('networkidle')
    await page.getByTestId('verdict-card-read').first().click()
    await page.waitForLoadState('networkidle')
    const overflow = await page.evaluate(() => {
      const d = document.documentElement
      return d.scrollWidth - d.clientWidth
    })
    expect(overflow).toBeLessThanOrEqual(0)
  })
})

test.describe('Verdict card on desktop (regression guard)', () => {
  test.use({ viewport: { width: 1280, height: 900 } })

  test('keeps its two-column layout (body + 188px side rail)', async ({ page }) => {
    await page.goto('/')
    await page.waitForLoadState('networkidle')
    const card = page.getByTestId('verdict-card').first()
    await expect(card).toBeVisible()
    const gtc = await card.evaluate(
      el => getComputedStyle(el as HTMLElement).gridTemplateColumns,
    )
    // "<bodypx> 188px" → two tracks, second ≈ 188px.
    const tracks = gtc.trim().split(/\s+/)
    expect(tracks).toHaveLength(2)
    expect(Math.round(parseFloat(tracks[1]))).toBe(188)
  })
})

test.describe('Watchlist drill-in (mobile)', () => {
  test('row opens full-screen detail, back returns to list', async ({ page }) => {
    await page.goto('/watchlist')
    await page.waitForLoadState('networkidle')
    // list visible, detail hidden initially
    await expect(page.getByTestId('watchlist-list')).toBeVisible()
    await expect(page.getByTestId('watchlist-detail')).toBeHidden()
    // tap first row → detail panel appears
    await page.locator('[data-testid="watchlist-item"]').first().click()
    await expect(page.getByTestId('watchlist-detail')).toBeVisible()
    // back → list again
    await page.getByTestId('watchlist-back').click()
    await expect(page.getByTestId('watchlist-list')).toBeVisible()
    await expect(page.getByTestId('watchlist-detail')).toBeHidden()
  })
})
