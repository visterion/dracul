<template>
  <nav
    class="bottom-nav hscroll-fade"
    :class="{ 'hscroll-fade--left': left, 'hscroll-fade--right': right }"
    data-testid="bottom-nav" aria-label="Main navigation"
  >
    <div ref="scrollEl" class="bottom-nav__scroll">
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
import { ref } from 'vue'
import { useNavItems } from '../../composables/useNavItems'
import { useEdgeFades } from '../../composables/useEdgeFades'
const navItems = useNavItems()
const scrollEl = ref<HTMLElement | null>(null)
const { left, right } = useEdgeFades(scrollEl)
</script>

<style scoped>
.bottom-nav {
  position: fixed;
  left: 0; right: 0; bottom: 0;
  z-index: 100;
  background: var(--crypt-black-elevated);
  border-top: var(--hairline);
  padding-bottom: env(safe-area-inset-bottom);
  --fade-bg: var(--crypt-black-elevated);
}
.bottom-nav__scroll {
  display: flex;
  overflow-x: auto;
  -webkit-overflow-scrolling: touch;
  scrollbar-width: none;
  scroll-snap-type: x proximity;
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
  scroll-snap-align: center;
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
