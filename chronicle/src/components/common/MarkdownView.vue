<template><div class="md" v-html="html" /></template>

<script setup lang="ts">
import { computed } from 'vue'
import { marked } from 'marked'
import DOMPurify from 'dompurify'

const props = defineProps<{ source: string }>()

// v-html is required to render the doc; DOMPurify is the safety boundary.
const html = computed(() =>
  DOMPurify.sanitize(marked.parse(props.source, { async: false }) as string),
)
</script>

<style scoped>
.md {
  color: var(--bone-ivory-dim);
  line-height: 1.65;
}

.md :deep(h1),
.md :deep(h2),
.md :deep(h3) {
  color: var(--bone-ivory);
  line-height: 1.25;
  margin: var(--space-4, 1.5rem) 0 var(--space-2, 0.5rem);
}

.md :deep(h1) {
  font-size: 1.5rem;
}
.md :deep(h2) {
  font-size: 1.25rem;
}
.md :deep(h3) {
  font-size: 1.1rem;
}

.md :deep(p) {
  color: var(--bone-ivory-dim);
  margin: 0 0 var(--space-2, 0.75rem);
}

.md :deep(a) {
  color: var(--cathedral-gold);
  text-decoration: none;
}
.md :deep(a:hover) {
  color: var(--cathedral-gold-bright, var(--cathedral-gold));
  text-decoration: underline;
}

.md :deep(ul),
.md :deep(ol) {
  padding-left: var(--space-4, 1.5rem);
  margin: 0 0 var(--space-2, 0.75rem);
}

.md :deep(li) {
  margin: 0.15rem 0;
}

.md :deep(table) {
  border-collapse: collapse;
  width: 100%;
  margin: var(--space-2, 0.75rem) 0;
}

.md :deep(th),
.md :deep(td) {
  border: 1px solid rgba(107, 107, 112, 0.4);
  padding: var(--space-2, 0.5rem);
  text-align: left;
}

.md :deep(th) {
  color: var(--bone-ivory);
  font-weight: 600;
}

.md :deep(code) {
  font-family: var(--font-mono, monospace);
  font-size: 0.9em;
  background: rgba(107, 107, 112, 0.18);
  padding: 0.1rem 0.35rem;
  border-radius: 3px;
}

.md :deep(pre) {
  font-family: var(--font-mono, monospace);
  background: rgba(107, 107, 112, 0.18);
  padding: var(--space-2, 0.75rem);
  border-radius: 4px;
  overflow-x: auto;
}

.md :deep(pre code) {
  background: none;
  padding: 0;
}

.md :deep(blockquote) {
  color: var(--ash-gray);
  border-left: 2px solid var(--cathedral-gold);
  margin: var(--space-2, 0.75rem) 0;
  padding-left: var(--space-2, 0.75rem);
}
</style>
