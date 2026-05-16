<template>
  <div class="tier-bar">
    <div class="tier-bar__header">
      <div>
        <span class="tier-bar__name">{{ name }}</span>
        <span class="tier-bar__models">{{ models }}</span>
      </div>
      <span class="tier-bar__amounts">
        <span class="tier-bar__used" :style="{ color: barColor }">
          ${{ usedUsd.toFixed(2) }}
        </span>
        <span class="tier-bar__sep"> / </span>
        <span class="tier-bar__budget">
          {{ budgetUsd > 0 ? '$' + budgetUsd.toFixed(2) : '∞' }}
        </span>
      </span>
    </div>
    <div class="tier-bar__track">
      <div
        class="tier-bar__fill"
        :style="{ width: fillWidth, background: barColor }"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  name: string
  models: string
  budgetUsd: number
  usedUsd: number
}>()

const pct = computed(() => props.budgetUsd > 0 ? props.usedUsd / props.budgetUsd : 0)

const fillWidth = computed(() => `${Math.min(pct.value * 100, 100).toFixed(1)}%`)

const barColor = computed(() => {
  if (props.budgetUsd === 0) return 'var(--ash-gray)'
  if (pct.value < 0.60) return '#4a7a4a'
  if (pct.value < 0.85) return 'var(--cathedral-gold)'
  return 'var(--blood-crimson)'
})
</script>

<style scoped>
.tier-bar { margin-bottom: var(--space-4); }
.tier-bar__header {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  margin-bottom: var(--space-1);
}
.tier-bar__name {
  font-size: var(--text-body);
  color: var(--bone-ivory);
  margin-right: var(--space-2);
}
.tier-bar__models {
  font-size: var(--text-micro);
  color: var(--ash-gray);
}
.tier-bar__amounts { font-size: var(--text-micro); }
.tier-bar__used { font-family: var(--font-mono); }
.tier-bar__sep { color: var(--ash-gray); }
.tier-bar__budget { color: var(--ash-gray); font-family: var(--font-mono); }
.tier-bar__track {
  height: 5px;
  background: rgba(255, 255, 255, 0.06);
  border-radius: 3px;
  overflow: hidden;
}
.tier-bar__fill {
  height: 100%;
  border-radius: 3px;
  transition: width 0.4s ease;
}
</style>
