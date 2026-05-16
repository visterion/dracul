<template>
  <div class="section-header" role="separator">
    <span class="section-header__label">{{ label }}</span>
  </div>
</template>

<script setup lang="ts">
defineProps<{ label: string }>()
</script>

<style scoped>
.section-header {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin: var(--space-6) 0 var(--space-4) 0;
}

/* Left em-dash — text only, no height/background */
.section-header::before {
  content: '──';
  color: var(--ash-gray);
  font-size: var(--text-micro);
  letter-spacing: 0.05em;
  flex-shrink: 0;
}

/* Trailing rule line — extends to the right */
.section-header::after {
  content: '';
  flex: 1 1 0;
  height: 1px;
  background: rgba(107, 107, 112, 0.25);
}

.section-header__label {
  font-size: var(--text-micro);
  color: var(--ash-gray);
  letter-spacing: 0.05em;
  text-transform: lowercase;
  white-space: nowrap;
  flex-shrink: 0;
}
</style>
