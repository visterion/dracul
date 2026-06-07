<template>
  <nav class="bottom-nav" data-testid="bottom-nav" aria-label="Main navigation">
    <div class="bottom-nav__scroll">
      <router-link
        v-for="item in navItems"
        :key="item.name"
        :to="{ name: item.name }"
        class="bottom-nav__tab"
        active-class="bottom-nav__tab--active"
      >
        <i class="ph bottom-nav__icon" :class="item.icon" aria-hidden="true"></i>
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
  border-top: var(--hairline);
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
  position: relative;
  flex: 1 0 auto;
  min-width: 72px;
  min-height: 56px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 3px;
  padding: var(--space-2) var(--space-3);
  text-decoration: none;
  color: var(--ash-gray);
  transition: color var(--transition-fast);
}
.bottom-nav__icon { font-size: 22px; line-height: 1; }
.bottom-nav__label { font-size: 10px; letter-spacing: 0.02em; white-space: nowrap; }
.bottom-nav__tab--active { color: var(--blood-crimson-bright); }
.bottom-nav__tab--active::after {
  content: "";
  position: absolute;
  top: 0; left: 20%; right: 20%;
  height: 2px;
  background: var(--blood-crimson);
}
</style>
