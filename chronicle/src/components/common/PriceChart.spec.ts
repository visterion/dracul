import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { defineComponent, h } from 'vue'

// Shallow-stub vue-echarts: capture the `option` prop instead of rendering a
// real canvas. Keeps this a smoke test, not a pixel test.
vi.mock('vue-echarts', () => ({
  default: defineComponent({
    name: 'VChart',
    props: ['option', 'autoresize'],
    setup(props) {
      return () => h('div', { class: 'v-chart-stub', 'data-option': JSON.stringify(props.option) })
    },
  }),
}))

import PriceChart from './PriceChart.vue'

function make(props: InstanceType<typeof PriceChart>['$props']) {
  return mount(PriceChart, { props })
}

describe('PriceChart', () => {
  it('renders a v-chart with one line series in the cathedral-gold colour', () => {
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
    expect(option.series[0].color).toBe('#B8945C')
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
})
