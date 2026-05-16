<template>
  <footer class="status-bar">
    <div class="status-bar__inner">
      <span class="status-bar__text font-mono tabular">{{ statusStore.summaryLine }}</span>
    </div>
  </footer>
</template>

<script setup lang="ts">
import { useStatusStore } from '../../stores/status'
const statusStore = useStatusStore()
</script>

<style scoped>
.status-bar {
  height: 32px;
  background-color: var(--crypt-black-deep);
  border-top: 1px solid rgba(184, 148, 92, 0.06);
  flex-shrink: 0;
}

.status-bar__inner {
  display: flex;
  align-items: center;
  height: 100%;
  max-width: 1600px;
  margin: 0 auto;
  padding: 0 var(--space-6);
}

.status-bar__text {
  font-size: var(--text-micro);
  color: var(--ash-gray);
  letter-spacing: 0.02em;
}
</style>
