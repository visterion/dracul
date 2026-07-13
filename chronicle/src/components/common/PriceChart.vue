<template>
  <v-chart
    class="price-chart"
    :style="{ height: `${H}px` }"
    :option="option"
    :autoresize="true"
  />
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { use } from 'echarts/core'
import { LineChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, MarkLineComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import VChart from 'vue-echarts'
import type { ComposeOption } from 'echarts/core'
import type { LineSeriesOption } from 'echarts/charts'
import type { GridComponentOption, TooltipComponentOption, MarkLineComponentOption } from 'echarts/components'

use([LineChart, GridComponent, TooltipComponent, MarkLineComponent, CanvasRenderer])

type EChartsOption = ComposeOption<
  LineSeriesOption | GridComponentOption | TooltipComponentOption | MarkLineComponentOption
>

interface Series {
  data: number[]
  color?: string
  fill?: string
}

const props = defineProps<{
  series: Series[]
  height?: number
  areaFill?: boolean
  labels?: { i: number; t: string }[]
  ariaLabel?: string
  /** When set, draws a dotted horizontal reference line at this value
   *  (e.g. the range's first value) so a "since start of timeframe" move
   *  reads visually against a fixed baseline. */
  baseline?: number | null
}>()

const H = computed(() => props.height ?? 220)

const DEFAULT_COLOR = '#B8945C' // var(--cathedral-gold)

const reduceMotion = computed(
  () =>
    typeof window !== 'undefined' &&
    window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
)

const n = computed(() => Math.max(0, ...props.series.map(s => s.data.length)))

const xAxisData = computed<string[]>(() => {
  if (props.labels && props.labels.length > 0) {
    const byIndex = new Map(props.labels.map(lb => [lb.i, lb.t]))
    return Array.from({ length: n.value }, (_, i) => byIndex.get(i) ?? '')
  }
  return Array.from({ length: n.value }, (_, i) => String(i))
})

function hexToRgba(hex: string, alpha: number): string {
  const m = /^#?([0-9a-f]{2})([0-9a-f]{2})([0-9a-f]{2})$/i.exec(hex)
  if (!m) return hex
  const r = parseInt(m[1], 16)
  const g = parseInt(m[2], 16)
  const b = parseInt(m[3], 16)
  return `rgba(${r},${g},${b},${alpha})`
}

const option = computed<EChartsOption>(() => ({
  animation: !reduceMotion.value,
  backgroundColor: 'transparent',
  grid: {
    left: 8,
    right: 8,
    top: 14,
    bottom: props.labels && props.labels.length > 0 ? 30 : 22,
    containLabel: false,
  },
  xAxis: {
    type: 'category',
    data: xAxisData.value,
    boundaryGap: false,
    axisLine: { show: false },
    axisTick: { show: false },
    axisLabel: {
      color: 'var(--bone-ivory-dim, #C9C5BC)',
      fontSize: 10,
      show: !!(props.labels && props.labels.length > 0),
    },
    splitLine: { show: false },
  },
  yAxis: {
    type: 'value',
    scale: true,
    axisLine: { show: false },
    axisTick: { show: false },
    axisLabel: { show: false },
    splitLine: {
      show: true,
      lineStyle: { color: 'var(--rule, rgba(245,241,232,0.05))', type: 'solid' },
    },
  },
  tooltip: {
    trigger: 'axis',
    axisPointer: { type: 'line' },
  },
  series: [
    ...props.series.map((s): LineSeriesOption => {
      const color = s.color ?? DEFAULT_COLOR
      return {
        type: 'line',
        data: s.data,
        color,
        showSymbol: false,
        smooth: false,
        lineStyle: { width: 2, color },
        emphasis: {
          focus: 'series',
          itemStyle: { color, borderColor: color },
        },
        areaStyle: props.areaFill
          ? {
              color: {
                type: 'linear',
                x: 0,
                y: 0,
                x2: 0,
                y2: 1,
                colorStops: [
                  { offset: 0, color: hexToRgba(s.fill ?? color, 0.28) },
                  { offset: 1, color: hexToRgba(s.fill ?? color, 0) },
                ],
              },
            }
          : undefined,
        markLine:
          props.baseline == null
            ? undefined
            : {
                symbol: 'none',
                silent: true,
                animation: false,
                lineStyle: { type: 'dashed', color: 'var(--bone-ivory-dim, #C9C5BC)', width: 1 },
                label: { show: false },
                data: [{ yAxis: props.baseline }],
              },
      }
    }),
  ],
}))
</script>

<style scoped>
.price-chart {
  width: 100%;
}
</style>
