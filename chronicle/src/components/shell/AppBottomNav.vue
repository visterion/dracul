<template>
  <nav class="bottom-nav" data-testid="bottom-nav" aria-label="Primary">
    <div class="bottom-nav__scroll">
      <router-link
        v-for="item in navItems"
        :key="item.name"
        :to="{ name: item.name }"
        class="bottom-nav__tab"
        active-class="bottom-nav__tab--active"
      >
        <span class="bottom-nav__icon" aria-hidden="true">{{ item.icon }}</span>
        <span class="bottom-nav__label">{{ item.label }}</span>
      </router-link>
    </div>
  </nav>
</template>

<script setup lang="ts">
import { useNavItems } from '../../composables/useNavItems'
const navItems = useNavItems()
</script>

<style scoped>
.bottom-nav {
  position: fixed;
  left: 0; right: 0; bottom: 0;
  z-index: 100;
  background: var(--crypt-black-elevated);
  border-top: 1px solid rgba(184, 148, 92, 0.12);
  padding-bottom: env(safe-area-inset-bottom);
}
.bottom-nav__scroll {
  display: flex;
  overflow-x: auto;
  -webkit-overflow-scrolling: touch;
  scrollbar-width: none;
}
.bottom-nav__scroll::-webkit-scrollbar { display: none; }
.bottom-nav__tab {
  flex: 0 0 auto;
  min-width: 72px;
  min-height: 44px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  padding: var(--space-2) var(--space-3);
  text-decoration: none;
  color: var(--ash-gray);
}
.bottom-nav__icon { font-size: 18px; line-height: 1; }
.bottom-nav__label { font-size: 11px; white-space: nowrap; }
.bottom-nav__tab--active { color: var(--blood-crimson); }
</style>
