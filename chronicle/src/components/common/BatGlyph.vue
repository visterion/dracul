<template>
  <img class="bat-glyph" src="/branding/mark-sm.png" alt="" :style="{ width: size + 'px', opacity: dim ? 0.85 : 1 }" />
</template>

<script setup lang="ts">
withDefaults(defineProps<{
  size?: number
  dim?: boolean
}>(), {
  size: 14,
  dim: true,
})
</script>

<!-- .bat-glyph is global in global.css — no scoped style needed -->
