import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { defineComponent, h } from 'vue'

// Shallow-stub vue-echarts: capture the `option` prop instead of rendering a
// real canvas. Keeps this a smoke test, not a pixel test.
//
// `capturedOptions` keeps the raw (non-JSON) option object too, since
// JSON.stringify silently drops function properties (e.g. tooltip.formatter).
const capturedOptions: Record<string, unknown>[] = []
vi.mock('vue-echarts', () => ({
  default: defineComponent({
    name: 'VChart',
    props: ['option', 'autoresize'],
    setup(props) {
      return () => {
        capturedOptions.push(props.option as Record<string, unknown>)
        return h('div', { class: 'v-chart-stub', 'data-option': JSON.stringify(props.option) })
      }
    },
  }),
}))

import PriceChart from './PriceChart.vue'

function make(props: InstanceType<typeof PriceChart>['$props']) {
  capturedOptions.length = 0
  return mount(PriceChart, { props })
}

function lastOption(): Record<string, unknown> {
  return capturedOptions[capturedOptions.length - 1]
}

describe('PriceChart', () => {
  it('renders a v-chart with one line series in the brighter gold line colour', () => {
    const w = make({
      series: [{ data: [10, 11, 9, 12] }],
      labels: [{ i: 0, t: 'Mon' }, { i: 3, t: 'Thu' }],
    })

    const stub = w.find('.v-chart-stub')
    expect(stub.exists()).toBe(true)

    const option = JSON.parse(stub.attributes('data-option')!)
    expect(option.series).toHaveLength(1)
    expect(option.series[0].type).toBe('line')
    expect(option.series[0].data).toEqual([10, 11, 9, 12])
    expect(option.series[0].color).toBe('#D4AF7A')
    expect(option.tooltip.trigger).toBe('axis')
  })

  it('honours a custom series color and areaFill', () => {
    const w = make({
      series: [{ data: [1, 2, 3], color: '#ff0000', fill: '#ff0000' }],
      areaFill: true,
    })

    const option = JSON.parse(w.find('.v-chart-stub').attributes('data-option')!)
    expect(option.series[0].color).toBe('#ff0000')
    expect(option.series[0].areaStyle).toBeTruthy()
  })

  it('applies the height prop to the wrapper style', () => {
    const w = make({ series: [{ data: [1, 2] }], height: 180 })
    expect((w.element as HTMLElement).style.height).toBe('180px')
  })

  it('uses `times` (not the point index) for the xAxis category data when provided', () => {
    make({
      series: [{ data: [10, 11, 9] }],
      times: ['14. Mai', '15. Mai', '16. Mai'],
    })

    const xAxis = lastOption().xAxis as { data: string[]; axisLabel: { show: boolean } }
    expect(xAxis.data).toEqual(['14. Mai', '15. Mai', '16. Mai'])
    expect(xAxis.axisLabel.show).toBe(true)
  })

  it('falls back to the point index when neither `times` nor `labels` are given', () => {
    make({ series: [{ data: [10, 11, 9] }] })

    const xAxis = lastOption().xAxis as { data: string[] }
    expect(xAxis.data).toEqual(['0', '1', '2'])
  })

  it('sets a tooltip formatter that renders the date and the formatted value', () => {
    make({
      series: [{ data: [197.915] }],
      times: ['14. Mai'],
    })

    const tooltip = lastOption().tooltip as { formatter: (params: unknown) => string }
    expect(typeof tooltip.formatter).toBe('function')

    const html = tooltip.formatter([
      { axisValueLabel: '14. Mai', color: '#D4AF7A', value: 197.915 },
    ])
    expect(html).toContain('14. Mai')
    expect(html).toContain('197.92') // default formatter: max 2 decimals
  })

  it('honours a custom valueFormatter in the tooltip', () => {
    make({
      series: [{ data: [42] }],
      times: ['14. Mai'],
      valueFormatter: (v: number) => `$${v.toFixed(0)}`,
    })

    const tooltip = lastOption().tooltip as { formatter: (params: unknown) => string }
    const html = tooltip.formatter([{ axisValueLabel: '14. Mai', color: '#D4AF7A', value: 42 }])
    expect(html).toContain('$42')
  })

  it('uses the brighter gold line color by default and a 2.5px line width', () => {
    const w = make({ series: [{ data: [1, 2, 3] }] })
    const option = JSON.parse(w.find('.v-chart-stub').attributes('data-option')!)
    expect(option.series[0].color).toBe('#D4AF7A')
    expect(option.series[0].lineStyle.width).toBe(2.5)
  })
})
