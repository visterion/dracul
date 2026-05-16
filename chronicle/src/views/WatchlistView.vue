<template>
  <div class="stub-view">
    <div class="stub-view__header">
      <h1 class="font-display">Watchlist</h1>
      <p class="stub-view__subtitle">Active instruments under Daywalker surveillance</p>
    </div>
    <div class="stub-view__placeholder">
      <span class="stub-view__bat">🦇</span>
      <p>The Strigoi are still returning from the night's hunt.</p>
    </div>
  </div>
</template>

<script setup lang="ts"></script>

<style scoped>
.stub-view {
  max-width: 1280px;
  margin: 0 auto;
  padding: var(--space-8) var(--space-6);
}
.stub-view__header { margin-bottom: var(--space-8); }
.stub-view__header h1 {
  font-size: var(--text-h1);
  line-height: 1.15;
  letter-spacing: -0.01em;
  color: var(--bone-ivory);
  margin: 0 0 var(--space-2) 0;
}
.stub-view__subtitle { color: var(--bone-ivory-dim); margin: 0; font-size: var(--text-body); }
.stub-view__placeholder {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: var(--space-12) 0;
  gap: var(--space-4);
  color: var(--ash-gray);
  font-style: italic;
}
.stub-view__bat { font-size: 32px; line-height: 1; }
</style>
