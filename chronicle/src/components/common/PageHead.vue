<template>
  <div class="page-head">
    <div class="page-head-row">
      <div class="page-head-text">
        <div v-if="$slots.eyebrow" class="page-eyebrow"><slot name="eyebrow" /></div>
        <h1 class="page-title">
          <slot name="title">{{ title }}</slot>
        </h1>
        <p v-if="sub || $slots.sub" class="page-sub">
          <slot name="sub">{{ sub }}</slot>
        </p>
      </div>
      <div v-if="$slots.right" class="page-head-right">
        <slot name="right" />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
defineProps<{
  title?: string
  sub?: string
}>()
</script>

<!-- .page-head, .page-head-row, .page-head-text, .page-eyebrow, .page-title,
     .page-sub, .page-head-right are all global in global.css — no scoped style needed -->
