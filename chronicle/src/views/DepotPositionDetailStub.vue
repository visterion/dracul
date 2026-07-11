<!-- Placeholder for Task C3 — the depot position detail view. Registered now
     so DepotsView (C2) can navigate to `depot-position-detail` from the
     positions table. C3 replaces this file's content; keep the route name. -->
<template>
  <div class="content-inner">
    <p>{{ route.params.connection }} / {{ route.params.symbol }}</p>
  </div>
</template>

<script setup lang="ts">
import { useRoute } from 'vue-router'

const route = useRoute()
</script>
