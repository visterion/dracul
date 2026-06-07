<template>
  <div class="chronicle">
    <!-- Loading skeleton -->
    <template v-if="store.loading">
      <v-skeleton-loader
        v-for="n in 3"
        :key="n"
        type="card"
        color="surface"
        class="chronicle__skeleton"
      />
    </template>

    <!-- Error state -->
    <div v-else-if="store.error" class="chronicle__error">
      <p>{{ store.error }}</p>
      <button class="chronicle__retry" @click="store.load()">{{ t('chronicle.error.retry') }}</button>
    </div>

    <!-- Content -->
    <template v-else>
      <!-- Morning Summary Banner -->
      <div class="chronicle__banner">
        <span>
          🦇
          <strong class="font-mono tabular">{{ store.prey.length }}</strong> {{ t('chronicle.banner.newPrey') }} ·
          <strong class="font-mono tabular">{{ store.verdicts.length }}</strong> {{ store.verdicts.length === 1 ? t('chronicle.banner.verdictSingular') : t('chronicle.banner.verdictPlural') }} ·
          <strong class="font-mono tabular">{{ store.alerts.length }}</strong> {{ store.alerts.length === 1 ? t('chronicle.banner.alertSingular') : t('chronicle.banner.alertPlural') }} ·
          <strong class="font-mono tabular">{{ store.pendingPatterns.length }}</strong> {{ store.pendingPatterns.length === 1 ? t('chronicle.banner.lessonSingular') : t('chronicle.banner.lessonPlural') }}
        </span>
      </div>

      <!-- Verdicts -->
      <SectionHeader :label="t('chronicle.sections.verdicts')" />
      <div v-if="store.verdicts.length > 0" class="chronicle__section" role="list">
        <VerdictCard
          v-for="verdict in store.verdicts"
          :key="verdict.id"
          :verdict="verdict"
        />
      </div>
      <p v-else class="chronicle__empty">{{ t('chronicle.emptyState.noVerdicts') }}</p>

      <!-- Individual Prey -->
      <SectionHeader :label="t('chronicle.sections.individualPrey')" />
      <div v-if="store.prey.length > 0" class="chronicle__section" role="list">
        <PreyCard
          v-for="prey in store.prey"
          :key="prey.id"
          :prey="prey"
        />
      </div>
      <p v-else class="chronicle__empty">{{ t('chronicle.emptyState.noPreyYet') }}</p>

      <!-- Daywalker Alerts -->
      <template v-if="store.alerts.length > 0">
        <SectionHeader :label="t('chronicle.sections.daywalkerAlerts')" />
        <div class="chronicle__alert-list" role="list" :aria-label="t('chronicle.ariaLabels.daywalkerAlerts')">
          <AlertRow
            v-for="alert in store.alerts"
            :key="alert.id"
            :alert="alert"
          />
        </div>
      </template>

      <!-- Pending Lessons -->
      <template v-if="store.pendingPatterns.length > 0">
        <SectionHeader :label="t('chronicle.sections.pendingLessons')" />
        <div class="chronicle__lesson-list" role="list" :aria-label="t('chronicle.ariaLabels.pendingLessons')">
          <PendingLessonRow
            v-for="pattern in store.pendingPatterns"
            :key="pattern.id"
            :pattern="pattern"
          />
        </div>
      </template>
    </template>
  </div>
</template>

<script setup lang="ts">
import { onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useChronicleStore } from '../stores/chronicle'
import SectionHeader from '../components/common/SectionHeader.vue'
import VerdictCard from '../components/common/VerdictCard.vue'
import PreyCard from '../components/common/PreyCard.vue'
import AlertRow from '../components/common/AlertRow.vue'
import PendingLessonRow from '../components/common/PendingLessonRow.vue'

const { t } = useI18n()
const store = useChronicleStore()
onMounted(() => store.load())
</script>

<style scoped>
.chronicle {
  max-width: 1280px;
  margin: 0 auto;
  padding: var(--space-6);
}

.chronicle__skeleton {
  margin-bottom: var(--space-4);
}

.chronicle__error {
  padding: var(--space-8) 0;
  color: var(--ash-gray);
  text-align: center;
}

.chronicle__retry {
  margin-top: var(--space-3);
  background: none;
  border: 1px solid var(--ash-gray);
  color: var(--bone-ivory);
  padding: var(--space-2) var(--space-4);
  border-radius: 4px;
  cursor: pointer;
  font-size: var(--text-body-sm);
  transition: border-color var(--transition-fast);
}

.chronicle__retry:hover {
  border-color: var(--cathedral-gold);
}

.chronicle__banner {
  background-color: var(--crypt-black-elevated);
  border: 1px solid rgba(184, 148, 92, 0.1);
  border-radius: 4px;
  padding: var(--space-5);
  margin-bottom: var(--space-2);
  font-size: var(--text-body-sm);
  color: var(--bone-ivory);
}

.chronicle__banner strong {
  color: var(--bone-ivory);
  font-weight: 600;
}

.chronicle__section {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}

.chronicle__alert-list,
.chronicle__lesson-list {
  background-color: var(--crypt-black-elevated);
  border: 1px solid rgba(184, 148, 92, 0.1);
  border-radius: 4px;
  padding: 0 var(--space-5);
}

.chronicle__empty {
  color: var(--ash-gray);
  font-style: italic;
  font-size: var(--text-body);
  padding: var(--space-4) 0;
  margin: 0;
}

@media (max-width: 959.98px) {
  .chronicle { padding-inline: var(--space-4); }
}
</style>
