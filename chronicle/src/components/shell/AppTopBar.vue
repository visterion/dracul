<template>
  <header class="top-bar">
    <div class="top-bar__inner">
      <!-- Wordmark: emblem crest + DRACUL with a crimson D -->
      <router-link :to="{ name: 'chronicle' }" class="top-bar__wordmark">
        <img src="/branding/mark.png" class="bat-mark-img" alt="Dracul crest" />
        <span class="word"><span class="d">D</span>RACUL</span>
      </router-link>

      <span v-if="!mobile" class="divider" aria-hidden="true"></span>

      <!-- Navigation -->
      <nav v-if="!mobile" class="top-bar__nav">
        <router-link
          v-for="item in navItems"
          :key="item.name"
          :to="{ name: item.name }"
          class="top-bar__tab"
          :class="{ 'top-bar__tab--active': isNavActive(item.matchPrefixes, route.path) }"
        >
          {{ item.label }}
        </router-link>
      </nav>

      <!-- Controls -->
      <div class="top-bar__controls" :class="{ 'top-bar__controls--mobile': mobile }">
        <button
          class="top-bar__icon-btn top-bar__live"
          :aria-label="t('app.liveAlerts.title')"
          :title="t('app.liveAlerts.title')"
          data-testid="live-toggle"
          @click="$emit('toggle-live')"
        >
          <i class="ph ph-bell" aria-hidden="true"></i>
          <span v-if="live.unread === 1" class="top-bar__live-dot" data-testid="live-unread" />
          <span v-else-if="live.unread >= 2" class="top-bar__live-badge" data-testid="live-unread">{{ live.unread }}</span>
        </button>
        <span v-if="me && !mobile" class="top-bar__user mono" data-testid="topbar-user">{{ me }}</span>
        <a v-if="me && me !== 'default'" class="top-bar__icon-btn" href="/cdn-cgi/access/logout"
           :aria-label="t('shell.logout')" :title="t('shell.logout')"><i class="ph ph-sign-out" /></a>
        <!-- User avatar placeholder — auth not in Phase 1 -->
        <div v-if="!mobile && !me" class="top-bar__avatar" aria-hidden="true">
          <span>V</span>
        </div>
      </div>
    </div>
  </header>
</template>

<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import { useLiveAlertsStore } from '../../stores/liveAlerts'
import { useNavItems, isNavActive } from '../../composables/useNavItems'
import { useMe } from '../../composables/useMe'

defineProps<{ mobile?: boolean }>()
defineEmits<{ 'toggle-live': [] }>()

const { t } = useI18n()
const live = useLiveAlertsStore()
const navItems = useNavItems()
const me = useMe()
const route = useRoute()
</script>

<style scoped>
.top-bar {
  height: var(--topbar-h);
  flex: 0 0 var(--topbar-h);
  background-color: var(--crypt-black-elevated);
  border-bottom: var(--hairline);
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

/* Wordmark: crest + DRACUL with crimson D */
.top-bar__wordmark {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  text-decoration: none;
  flex-shrink: 0;
  user-select: none;
}
.bat-mark-img {
  height: 30px;
  width: auto;
  display: block;
  filter: drop-shadow(0 0 8px rgba(161, 29, 44, 0.25));
}
.word {
  font-family: var(--font-display);
  font-weight: 600;
  font-size: 28px;
  letter-spacing: 0.16em;
  text-transform: uppercase;
  color: var(--bone-ivory);
  line-height: 1;
}
.word .d {
  color: var(--blood-crimson);
}

.divider {
  width: 1px;
  height: 26px;
  background: rgba(184, 148, 92, 0.18);
  flex-shrink: 0;
}

.top-bar__nav {
  display: flex;
  align-items: center;
  gap: var(--space-1);
  flex: 1;
  /* Never push the controls out of the row: allow the nav to shrink below
     its content width and scroll internally instead. */
  min-width: 0;
  overflow-x: auto;
  scrollbar-width: none;
  /* overflow-x:auto forces overflow-y to 'auto' too; keep the active-tab
     underline (::after at bottom:-2px) inside the scrollable box. */
  padding: 4px 0;
}
.top-bar__nav::-webkit-scrollbar { display: none; }

.top-bar__tab {
  position: relative;
  font-family: var(--font-body);
  font-size: var(--text-body-sm);
  color: var(--ash-gray);
  text-decoration: none;
  padding: var(--space-2) var(--space-3);
  border-radius: 4px;
  transition: color var(--transition-fast);
  white-space: nowrap;
}

.top-bar__tab:hover {
  color: var(--bone-ivory-dim);
}

.top-bar__tab--active {
  color: var(--bone-ivory);
}
.top-bar__tab--active::after {
  content: "";
  position: absolute;
  left: var(--space-3);
  right: var(--space-3);
  bottom: -2px;
  height: 2px;
  background: var(--blood-crimson);
  border-radius: 2px;
}

.top-bar__controls {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  flex-shrink: 0;
}

/* With the centered nav hidden on mobile, push the remaining controls
   (the live bell) to the far right so the slim header reads
   wordmark | bell. */
.top-bar__controls--mobile {
  margin-left: auto;
}

.top-bar__icon-btn {
  width: 38px;
  height: 38px;
  display: grid;
  place-items: center;
  border-radius: 4px;
  background: transparent;
  border: none;
  cursor: pointer;
  font-size: 20px;
  color: var(--bone-ivory-dim);
  transition: color var(--transition-fast), background var(--transition-fast);
  position: relative;
}

.top-bar__icon-btn:hover {
  color: var(--cathedral-gold);
  background: rgba(184, 148, 92, 0.06);
}

.top-bar__avatar {
  width: 38px;
  height: 38px;
  border-radius: 50%;
  background-color: var(--blood-crimson-muted);
  display: grid;
  place-items: center;
  font-family: var(--font-display);
  font-size: 18px;
  font-weight: 600;
  color: var(--bone-ivory);
  cursor: pointer;
  flex-shrink: 0;
}

.top-bar__user { font-size: 11px; color: var(--ash-gray); max-width: 160px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.top-bar__live { position: relative; }
.top-bar__live-dot {
  position: absolute; top: 7px; right: 8px; width: 7px; height: 7px; border-radius: 50%;
  background: var(--blood-crimson-bright);
}
.top-bar__live-badge {
  position: absolute; top: 2px; right: 0; min-width: 16px; height: 16px; padding: 0 4px;
  border-radius: 8px; background: var(--blood-crimson); color: var(--bone-ivory);
  font-size: 10px; line-height: 16px; text-align: center; font-weight: 600;
}

/* Narrow desktop (960–1279px): tighten spacing so all 7 tabs + controls fit;
   the email text yields first (the logout button itself always stays). */
@media (max-width: 1279.98px) {
  .top-bar__inner { gap: var(--space-4); padding: 0 var(--space-4); }
  .top-bar__user { display: none; }
}
</style>
