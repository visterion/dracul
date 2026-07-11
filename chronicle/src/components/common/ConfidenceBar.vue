<template>
  <div class="conf-row" role="meter" :aria-valuenow="score" :aria-valuemin="0" :aria-valuemax="1">
    <span class="conf-score">{{ formatNumber(score, 2) }}</span>
    <span class="conf-track"><span class="conf-fill" :class="cls" :style="{ width: `${score * 100}%` }" /></span>
    <span class="conf-tag">{{ cls }}</span>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { formatNumber } from '../../utils/format'

const props = defineProps<{ score: number }>()

// confClass per styles.css / prototype: >0.75 high, >=0.5 mid, else low
const cls = computed(() => (props.score > 0.75 ? 'high' : props.score >= 0.5 ? 'mid' : 'low'))
</script>

<style scoped>
/* .conf-* are NOT global → scoped (mirrors styles.css:194-202) */
.conf-row {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}

.conf-score {
  font-family: var(--font-mono);
  font-variant-numeric: tabular-nums;
  font-size: var(--text-body);
  color: var(--bone-ivory);
  width: 3.4ch;
}

.conf-track {
  flex: 1;
  height: 6px;
  background: rgba(245, 241, 232, 0.08);
  border-radius: 3px;
  overflow: hidden;
}

.conf-fill {
  height: 100%;
  border-radius: 3px;
  transition: width var(--transition-slow);
}

.conf-fill.high { background: var(--blood-crimson); }
.conf-fill.mid { background: var(--cathedral-gold); }
.conf-fill.low { background: var(--ash-gray); }

.conf-tag {
  font-size: var(--text-micro);
  text-transform: uppercase;
  letter-spacing: 0.08em;
  color: var(--bone-ivory-dim);
  width: 4ch;
  text-align: right;
}
</style>
