<template>
  <div class="stat-tile">
    <div class="st-label">
      <i v-if="icon" :class="['ph', icon]" />
      {{ label }}
    </div>
    <div :class="['st-value', 'mono', valueClass]">
      {{ value }}<span v-if="unit" class="st-unit"> {{ unit }}</span>
    </div>
    <div v-if="foot" class="st-foot">
      <span :class="footClass">{{ foot }}</span>
    </div>
  </div>
</template>

<script setup lang="ts">
defineProps<{
  label: string
  icon?: string
  value: string | number
  unit?: string
  foot?: string
  footClass?: string
  valueClass?: string
}>()
</script>

<!-- .stat-tile, .st-label, .st-value, .st-unit, .st-foot are all global in global.css — no scoped style needed -->
