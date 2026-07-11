<!-- Generic horizontally-scrollable card row used by DepotPositionDetailView
     (News/Ereignisse/Insights/Finanzen). The parent decides whether to render
     this at all — an empty/null section must vanish entirely, never show an
     empty row — so this component simply lays out whatever cards it is given. -->
<template>
  <section class="info-card-row" :data-testid="testid">
    <div class="section-head">{{ title }}</div>
    <div class="icr-track">
      <slot />
    </div>
  </section>
</template>

<script setup lang="ts">
defineProps<{
  title: string
  testid?: string
}>()
</script>

<style scoped>
.info-card-row { display: flex; flex-direction: column; gap: var(--space-3); }
.icr-track {
  display: flex;
  gap: var(--space-3);
  overflow-x: auto;
  scroll-snap-type: x mandatory;
  padding-bottom: var(--space-2);
}
.icr-track > :deep(*) {
  scroll-snap-align: start;
  flex: 0 0 auto;
}
</style>
