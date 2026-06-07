<template>
  <div class="section-head">
    <span class="sh-rule" />
    <slot>{{ label }}</slot>
    <span v-if="sub" class="sh-sub">{{ sub }}</span>
  </div>
</template>

<script setup lang="ts">
defineProps<{ label?: string; sub?: string }>()
</script>
