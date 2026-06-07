<template>
  <div class="consensus-ring" :style="{ width: size + 'px', height: size + 'px' }" role="meter" :aria-valuenow="value" :aria-valuemin="0" :aria-valuemax="1" :aria-label="`Consensus score ${value.toFixed(2)}`">
    <svg :width="size" :height="size" :viewBox="`0 0 ${size} ${size}`" aria-hidden="true">
      <circle
        :cx="size / 2"
        :cy="size / 2"
        :r="r"
        fill="none"
        stroke="rgba(245,241,232,0.08)"
        stroke-width="4"
      />
      <circle
        :cx="size / 2"
        :cy="size / 2"
        :r="r"
        fill="none"
        stroke="var(--blood-crimson)"
        stroke-width="4"
        stroke-linecap="round"
        :stroke-dasharray="c"
        :stroke-dashoffset="off"
        :transform="`rotate(-90 ${size / 2} ${size / 2})`"
      />
    </svg>
    <div class="consensus-ring-num mono">{{ value.toFixed(2) }}</div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(defineProps<{
  value: number
  size?: number
}>(), {
  size: 64,
})

const r = computed(() => (props.size - 8) / 2)
const c = computed(() => 2 * Math.PI * r.value)
const off = computed(() => c.value * (1 - props.value))
</script>

<style scoped>
/* .consensus-ring and .consensus-ring-num are NOT in global.css — defined here */
.consensus-ring {
  position: relative;
  display: grid;
  place-items: center;
}

.consensus-ring-num {
  position: absolute;
  inset: 0;
  display: grid;
  place-items: center;
  font-size: var(--text-body);
  color: var(--bone-ivory);
}
</style>
