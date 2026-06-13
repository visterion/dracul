<template>
  <v-app>
    <div class="app-layout">
      <AppTopBar :mobile="smAndDown" @toggle-live="toggleLive" />
      <main class="app-main" :class="{ 'app-main--mobile': smAndDown }">
        <router-view />
      </main>
      <AppStatusBar v-if="!smAndDown" />
      <AppBottomNav v-if="smAndDown" />
    </div>
    <LiveAlertPanel :open="panelOpen" @close="panelOpen = false" />
  </v-app>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useDisplay } from 'vuetify'
import AppTopBar from './components/shell/AppTopBar.vue'
import AppStatusBar from './components/shell/AppStatusBar.vue'
import AppBottomNav from './components/shell/AppBottomNav.vue'
import LiveAlertPanel from './components/shell/LiveAlertPanel.vue'
import { useStatusStore } from './stores/status'
import { useLiveAlertsStore } from './stores/liveAlerts'

const { smAndDown } = useDisplay()

const statusStore = useStatusStore()
const liveStore = useLiveAlertsStore()
const panelOpen = ref(false)

function toggleLive() {
  panelOpen.value = !panelOpen.value
  if (panelOpen.value) liveStore.markRead()
}

onMounted(() => {
  statusStore.load()
  liveStore.connect()
})
</script>

<style>
.app-layout {
  display: flex;
  flex-direction: column;
  height: 100vh;
  background-color: var(--crypt-black);
  position: relative;
}

/* Ambient moonlight glow bleeding from the top-right corner. */
.app-layout::before {
  content: "";
  position: fixed;
  top: -280px;
  right: -200px;
  width: 680px;
  height: 680px;
  background: radial-gradient(circle, rgba(196, 196, 202, 0.05) 0%, rgba(184, 148, 92, 0.022) 32%, transparent 62%);
  pointer-events: none;
  z-index: 0;
}

.app-main {
  flex: 1;
  overflow-y: auto;
  overflow-x: hidden;
  position: relative;
  z-index: 1;
}

/* Clear the fixed bottom nav on mobile so content isn't hidden behind it. */
.app-main--mobile {
  padding-bottom: calc(64px + env(safe-area-inset-bottom));
}
</style>
