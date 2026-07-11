<template>
  <div class="toast-stack" aria-live="polite">
    <TransitionGroup name="toast">
      <div
        v-for="toast in toasts"
        :key="toast.id"
        class="toast"
        :class="`toast--${toast.type}`"
        role="status"
        data-testid="app-toast"
        @click="dismiss(toast.id)"
      >
        <i class="ph" :class="toast.type === 'error' ? 'ph-warning-circle' : 'ph-check-circle'" aria-hidden="true" />
        <span>{{ toast.message }}</span>
      </div>
    </TransitionGroup>
  </div>
</template>

<script setup lang="ts">
import { useToast } from '../../composables/useToast'
const { toasts, dismiss } = useToast()
</script>

<style scoped>
.toast-stack {
  position: fixed;
  bottom: calc(64px + env(safe-area-inset-bottom) + var(--space-4)); /* clears mobile bottom nav */
  left: 50%;
  transform: translateX(-50%);
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-2);
  z-index: 300;
  pointer-events: none;
}
@media (min-width: 960px) {
  .toast-stack { bottom: calc(32px + var(--space-4)); } /* clears desktop status bar */
}
.toast {
  pointer-events: auto;
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  background: var(--crypt-black-elevated);
  border: var(--hairline);
  border-left: 3px solid var(--signal-positive);
  border-radius: 4px;
  color: var(--bone-ivory);
  font-size: var(--text-body-sm);
  padding: var(--space-3) var(--space-4);
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.5);
  cursor: pointer;
}
.toast--success .ph { color: var(--signal-positive-bright); }
.toast--error { border-left-color: var(--blood-crimson); }
.toast--error .ph { color: var(--blood-crimson-bright); }
.toast-enter-active, .toast-leave-active { transition: opacity 0.2s ease, transform 0.2s ease; }
.toast-enter-from, .toast-leave-to { opacity: 0; transform: translateY(8px); }
</style>
