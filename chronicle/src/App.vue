<template>
  <v-app>
    <div class="app-layout">
      <AppTopBar @toggle-live="toggleLive" />
      <main class="app-main">
        <router-view />
      </main>
      <AppStatusBar />
    </div>
    <LiveAlertPanel :open="panelOpen" @close="panelOpen = false" />
  </v-app>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import AppTopBar from './components/shell/AppTopBar.vue'
import AppStatusBar from './components/shell/AppStatusBar.vue'
import LiveAlertPanel from './components/shell/LiveAlertPanel.vue'
import { useStatusStore } from './stores/status'
import { useLiveAlertsStore } from './stores/liveAlerts'

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
}

.app-main {
  flex: 1;
  overflow-y: auto;
}
</style>
