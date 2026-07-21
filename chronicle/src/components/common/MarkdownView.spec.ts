// @vitest-environment jsdom
// DOMPurify's node-iterator/serialization is unreliable under happy-dom
// 20.10.6 (drops <h1>, keeps javascript: hrefs). jsdom mirrors real-browser
// behaviour, so it is the correct DOM for exercising the sanitizer.
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import MarkdownView from './MarkdownView.vue'

describe('MarkdownView', () => {
  it('renders headings, tables and lists from markdown', () => {
    const source = '# H\n\nText\n\n| a | b |\n|---|---|\n| 1 | 2 |\n\n- x\n- y'
    const w = mount(MarkdownView, { props: { source } })
    const html = w.html()
    expect(html).toContain('<h1')
    expect(html).toMatch(/<h1[^>]*>H<\/h1>/)
    expect(html).toContain('<table')
    expect(html).toMatch(/<td[^>]*>1<\/td>/)
    expect((html.match(/<li/g) ?? []).length).toBe(2)
  })

  it('sanitizes dangerous markup (no onerror, no javascript: href)', () => {
    const source = '<img src=x onerror="alert(1)">\n\n[link](javascript:alert(1))'
    const w = mount(MarkdownView, { props: { source } })
    const html = w.html()
    expect(html).not.toContain('onerror')
    expect(html).not.toContain('javascript:')
  })
})
