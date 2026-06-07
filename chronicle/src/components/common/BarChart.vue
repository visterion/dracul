<template>
  <svg
    class="svg-chart"
    :viewBox="`0 0 ${W} ${H}`"
    preserveAspectRatio="none"
    role="img"
  >
    <rect
      v-for="(v, i) in props.data"
      :key="i"
      :x="i * (bw + gap)"
      :y="yPos(v)"
      :width="bw"
      :height="Math.max(0, H - padB - yPos(v))"
      :fill="threshold !== undefined && v >= threshold ? 'var(--blood-crimson)' : 'rgba(184,148,92,0.55)'"
      rx="1"
    />
  </svg>
</template>

<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  data: number[]
  height?: number
  threshold?: number
}>()

const W = 720
const H = computed(() => props.height ?? 160)
const padT = 12
const padB = 18
const gap = 2

const maxVal = computed(() => Math.max(...props.data, 0.0001) * 1.1)
const n = computed(() => props.data.length)
const bw = computed(() => W / n.value - gap)

function yPos(v: number): number {
  return padT + (1 - v / maxVal.value) * (H.value - padT - padB)
}
</script>
