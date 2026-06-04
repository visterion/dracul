<template>
  <header class="top-bar">
    <div class="top-bar__inner">
      <!-- Wordmark -->
      <router-link :to="{ name: 'chronicle' }" class="top-bar__wordmark">
        DRACUL
      </router-link>

      <!-- Navigation -->
      <nav class="top-bar__nav">
        <router-link
          v-for="tab in navTabs"
          :key="tab.name"
          :to="{ name: tab.name }"
          class="top-bar__tab"
          active-class="top-bar__tab--active"
        >
          {{ tab.label }}
        </router-link>
      </nav>

      <!-- Controls -->
      <div class="top-bar__controls">
        <button
          class="top-bar__icon-btn top-bar__live"
          aria-label="Live alerts"
          title="Live alerts"
          data-testid="live-toggle"
          @click="$emit('toggle-live')"
        >
          🔔
          <span class="top-bar__live-dot" :class="`top-bar__live-dot--${live.status}`"></span>
          <span v-if="live.unread > 0" class="top-bar__live-badge" data-testid="live-unread">
            {{ live.unread }}
          </span>
        </button>
        <!-- Moon icon placeholder — cream/dark toggle (not implemented in scaffold) -->
        <button class="top-bar__icon-btn" aria-label="Toggle light mode" title="Light mode (coming soon)">
          🌙
        </button>
        <!-- User avatar placeholder — auth not in Phase 1 -->
        <div class="top-bar__avatar" aria-label="User account">
          <span>V</span>
        </div>
      </div>
    </div>
  </header>
</template>

<script setup lang="ts">
import { useLiveAlertsStore } from '../../stores/liveAlerts'

defineEmits<{ 'toggle-live': [] }>()

const live = useLiveAlertsStore()

const navTabs = [
  { name: 'chronicle', label: 'chronicle' },
  { name: 'watchlist', label: 'watchlist' },
  { name: 'pattern-library', label: 'pattern library' },
  { name: 'vistierie', label: 'vistierie' },
  { name: 'backtest', label: 'backtest' },
  { name: 'settings', label: 'settings' },
] as const
</script>

<style scoped>
.top-bar {
  height: 64px;
  background-color: var(--crypt-black-elevated);
  border-bottom: 1px solid rgba(184, 148, 92, 0.1);
  flex-shrink: 0;
  z-index: 100;
}

.top-bar__inner {
  display: flex;
  align-items: center;
  height: 100%;
  max-width: 1600px;
  margin: 0 auto;
  padding: 0 var(--space-6);
  gap: var(--space-6);
}

.top-bar__wordmark {
  font-family: var(--font-display);
  font-size: 22px;
  font-weight: 600;
  letter-spacing: 0.12em;
  color: var(--blood-crimson);
  text-decoration: none;
  text-transform: uppercase;
  flex-shrink: 0;
  transition: color var(--transition-fast);
}

.top-bar__wordmark:hover {
  color: var(--blood-crimson-bright);
}

.top-bar__nav {
  display: flex;
  align-items: center;
  gap: var(--space-1);
  flex: 1;
  justify-content: center;
}

.top-bar__tab {
  font-family: var(--font-body);
  font-size: var(--text-body-sm);
  color: var(--ash-gray);
  text-decoration: none;
  padding: var(--space-2) var(--space-3);
  border-bottom: 2px solid transparent;
  transition: color var(--transition-fast), border-color var(--transition-fast);
  white-space: nowrap;
}

.top-bar__tab:hover {
  color: var(--bone-ivory-dim);
}

.top-bar__tab--active {
  color: var(--bone-ivory);
  border-bottom-color: var(--blood-crimson);
}

.top-bar__controls {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  flex-shrink: 0;
}

.top-bar__icon-btn {
  background: none;
  border: none;
  cursor: pointer;
  font-size: 18px;
  opacity: 0.6;
  transition: opacity var(--transition-fast);
  padding: var(--space-1);
  line-height: 1;
}

.top-bar__icon-btn:hover {
  opacity: 1;
}

.top-bar__avatar {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  background-color: var(--blood-crimson-muted);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: var(--text-body-sm);
  font-weight: 600;
  color: var(--bone-ivory);
  cursor: pointer;
  flex-shrink: 0;
}

.top-bar__live { position: relative; }
.top-bar__live-dot {
  position: absolute; top: 2px; right: 0; width: 7px; height: 7px; border-radius: 50%;
  background: var(--ash-gray);
}
.top-bar__live-dot--open { background: var(--signal-positive); }
.top-bar__live-dot--connecting { background: var(--cathedral-gold); }
.top-bar__live-dot--closed { background: var(--ash-gray); }
.top-bar__live-badge {
  position: absolute; top: -4px; right: -6px; min-width: 16px; height: 16px; padding: 0 4px;
  border-radius: 8px; background: var(--blood-crimson); color: var(--bone-ivory);
  font-size: 10px; line-height: 16px; text-align: center; font-weight: 600;
}
</style>
