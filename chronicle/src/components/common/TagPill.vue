<template>
  <span :class="['tag-pill', tone]"><slot /></span>
</template>

<script setup lang="ts">
withDefaults(defineProps<{
  tone?: 'gold' | 'crimson' | 'green' | 'ash'
}>(), {
  tone: 'gold',
})
</script>

<!-- .tag-pill and .tag-pill.{tone} are global in global.css — no scoped style needed -->
