<template>
  <v-app>
    <div class="app-layout">
      <AppTopBar />
      <main class="app-main">
        <router-view />
      </main>
      <AppStatusBar />
    </div>
  </v-app>
</template>

<script setup lang="ts">
import { onMounted } from 'vue'
import AppTopBar from './components/shell/AppTopBar.vue'
import AppStatusBar from './components/shell/AppStatusBar.vue'
import { useStatusStore } from './stores/status'

const statusStore = useStatusStore()
onMounted(() => statusStore.load())
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
