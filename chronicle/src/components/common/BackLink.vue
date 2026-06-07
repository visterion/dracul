<template>
  <button class="back-link" @click="$emit('click')">
    <i class="ph ph-arrow-left" />
    <slot />
  </button>
</template>

<script setup lang="ts">
defineEmits<{
  (e: 'click'): void
}>()
</script>

<!-- .back-link is global in global.css — no scoped style needed -->
