<template>
  <v-chart
    class="price-chart"
    role="img"
    :aria-label="ariaLabel"
    :style="{ height: `${H}px` }"
    :option="option"
    :autoresize="true"
  />
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { use } from 'echarts/core'
import { LineChart as EChartsLineSeries } from 'echarts/charts'
import { GridComponent, TooltipComponent, MarkLineComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import VChart from 'vue-echarts'
import type { ComposeOption } from 'echarts/core'
import type { LineSeriesOption } from 'echarts/charts'
import type { GridComponentOption, TooltipComponentOption, MarkLineComponentOption } from 'echarts/components'

use([EChartsLineSeries, GridComponent, TooltipComponent, MarkLineComponent, CanvasRenderer])

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
  /** One display date/time string per data point (same length/order as
   *  `series[0].data`). When set, used as the x-axis category data so the
   *  axis-tooltip header shows the actual date instead of the point index. */
  times?: string[]
  /** Formats the value shown in the tooltip. Defaults to a locale number
   *  with at most 2 decimals. */
  valueFormatter?: (v: number) => string
  ariaLabel?: string
  /** When set, draws a dotted horizontal reference line at this value
   *  (e.g. the range's first value) so a "since start of timeframe" move
   *  reads visually against a fixed baseline. */
  baseline?: number | null
}>()

const H = computed(() => props.height ?? 220)

const AREA_BASE_COLOR = '#B8945C' // var(--cathedral-gold) — base gradient color
const LINE_COLOR = '#D4AF7A' // brighter gold — line reads too faint against the near-black background at the base tone

// Canvas can't resolve CSS var() strings. A view passing color:'var(--cathedral-gold)'
// would make ECharts fall back to its default palette (whose 4th colour is a red) — the
// "line is always red" symptom. Coerce any non-literal colour to the gold fallback.
function resolveColor(c: string | undefined, fallback: string): string {
  return c && !c.startsWith('var(') ? c : fallback
}

const DEFAULT_VALUE_FORMATTER = (v: number) =>
  new Intl.NumberFormat(undefined, { maximumFractionDigits: 2 }).format(v)

const reduceMotion = computed(
  () =>
    typeof window !== 'undefined' &&
    window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
)

const n = computed(() => Math.max(0, ...props.series.map(s => s.data.length)))

const hasAxisLabels = computed(
  () => (props.times && props.times.length > 0) || (props.labels && props.labels.length > 0)
)

const xAxisData = computed<string[]>(() => {
  if (props.times && props.times.length > 0) return props.times
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
    bottom: hasAxisLabels.value ? 30 : 22,
    containLabel: false,
  },
  xAxis: {
    type: 'category',
    data: xAxisData.value,
    boundaryGap: false,
    axisLine: { show: false },
    axisTick: { show: false },
    axisLabel: {
      color: '#C9C5BC', // --bone-ivory-dim (literal: canvas can't resolve CSS var())
      fontSize: 10,
      show: hasAxisLabels.value,
      // 'auto' (the category-axis default) thins overlapping labels itself —
      // explicit here so a narrow phone viewport never crowds the full `times` array.
      interval: 'auto',
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
      lineStyle: { color: 'rgba(245,241,232,0.05)', type: 'solid' }, // --rule (literal for canvas)
    },
  },
  tooltip: {
    trigger: 'axis',
    axisPointer: { type: 'line', lineStyle: { color: 'rgba(184,148,92,0.5)', width: 1 } },
    backgroundColor: '#16161D', // --surface-2 (dark, on-theme; ECharts default is white)
    borderColor: 'rgba(184,148,92,0.35)',
    borderWidth: 1,
    padding: [8, 10],
    textStyle: { color: '#F5F1E8', fontSize: 12 },
    formatter: (params: unknown) => {
      const arr = Array.isArray(params) ? params : [params]
      if (arr.length === 0) return ''
      const fmt = props.valueFormatter ?? DEFAULT_VALUE_FORMATTER
      type AxisTooltipParam = { axisValueLabel?: string; axisValue?: string; color?: string; value?: unknown }
      const first = arr[0] as AxisTooltipParam
      const date = first.axisValueLabel ?? first.axisValue ?? ''
      const rows = (arr as AxisTooltipParam[])
        .map(p => {
          const raw = Array.isArray(p.value) ? p.value[p.value.length - 1] : p.value
          const val = typeof raw === 'number' ? fmt(raw) : String(raw ?? '')
          return `<div style="display:flex;align-items:center;gap:6px;margin-top:4px;">`
            + `<span style="display:inline-block;width:8px;height:8px;border-radius:50%;background:${p.color ?? LINE_COLOR};"></span>`
            + `<span>${val}</span>`
            + `</div>`
        })
        .join('')
      return `<div style="font-size:12px;line-height:1.4;">`
        + `<div style="color:#F5F1E8;font-weight:600;">${date}</div>`
        + rows
        + `</div>`
    },
  },
  series: [
    ...props.series.map((s): LineSeriesOption => {
      const color = resolveColor(s.color, LINE_COLOR)
      const areaColor = resolveColor(s.fill ?? s.color, AREA_BASE_COLOR)
      return {
        type: 'line',
        data: s.data,
        color,
        showSymbol: false,
        smooth: false,
        lineStyle: { width: 2.5, color },
        emphasis: {
          focus: 'none',
          itemStyle: { color, borderColor: color },
        },
        // Keep the line/area fully opaque even if ECharts enters a blur state on
        // axis-tooltip hover — with a single series, focus:'series' faded the whole
        // line out on tap (the line "disappeared" when you clicked into the chart).
        blur: {
          lineStyle: { opacity: 1 },
          areaStyle: { opacity: 1 },
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
                  { offset: 0, color: hexToRgba(areaColor, 0.28) },
                  { offset: 1, color: hexToRgba(areaColor, 0) },
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
                lineStyle: { type: 'dashed', color: '#C9C5BC', width: 1 }, // --bone-ivory-dim (literal for canvas)
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
