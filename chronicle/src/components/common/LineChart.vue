<template>
  <svg
    class="svg-chart"
    :viewBox="`0 0 ${W} ${H}`"
    preserveAspectRatio="none"
    role="img"
    :aria-label="ariaLabel"
  >
    <line
      v-for="(gy, i) in gridYs"
      :key="i"
      class="grid-line"
      :x1="padL"
      :x2="W - padR"
      :y1="gy"
      :y2="gy"
    />
    <g v-for="(s, si) in props.series" :key="si">
      <path
        v-if="props.areaFill && s.fill"
        :d="areaPath(s)"
        :fill="s.fill"
      />
      <path
        :d="linePath(s)"
        fill="none"
        :stroke="s.color"
        stroke-width="2"
        stroke-linejoin="round"
        stroke-linecap="round"
      />
    </g>
    <text
      v-for="(lb, i) in props.labels"
      :key="i"
      class="axis-label"
      :x="xPos(lb.i)"
      :y="H - 8"
      text-anchor="middle"
    >{{ lb.t }}</text>
  </svg>
</template>

<script setup lang="ts">
import { computed } from 'vue'

interface Series {
  data: number[]
  color: string
  fill?: string
}

const props = defineProps<{
  series: Series[]
  height?: number
  areaFill?: boolean
  labels?: { i: number; t: string }[]
  ariaLabel?: string
}>()

const W = 720
const H = computed(() => props.height ?? 220)
const padL = 8
const padR = 8
const padT = 14
const padB = computed(() => (props.labels ? 30 : 22))

const all = computed(() => props.series.flatMap(s => s.data))
const minVal = computed(() => Math.min(...all.value, 0))
const maxVal = computed(() => Math.max(...all.value))
const range = computed(() => maxVal.value - minVal.value || 1)
const n = computed(() => Math.max(...props.series.map(s => s.data.length)))

function xPos(i: number): number {
  const denom = n.value - 1
  // when there is only one point, centre it
  if (denom <= 0) return padL + (W - padL - padR) / 2
  return padL + (i / denom) * (W - padL - padR)
}

function yPos(v: number): number {
  return padT + (1 - (v - minVal.value) / range.value) * (H.value - padT - padB.value)
}

const gridYs = computed(() =>
  [0, 0.25, 0.5, 0.75, 1].map(f => padT + f * (H.value - padT - padB.value))
)

function linePath(s: Series): string {
  if (s.data.length === 0) return ''
  return s.data
    .map((v, i) => `${i === 0 ? 'M' : 'L'} ${xPos(i).toFixed(1)} ${yPos(v).toFixed(1)}`)
    .join(' ')
}

function areaPath(s: Series): string {
  if (s.data.length === 0) return ''
  const line = linePath(s)
  const last = s.data.length - 1
  return `${line} L ${xPos(last).toFixed(1)} ${H.value - padB.value} L ${xPos(0).toFixed(1)} ${H.value - padB.value} Z`
}
</script>
