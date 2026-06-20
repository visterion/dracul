<template>
  <span class="money">
    <span class="money-primary">{{ formatMoney(amount, currency, locale) }}</span>
    <span v-if="showNative" class="money-native">
      ({{ t('common.origPrice') }} {{ formatMoney(nativeAmount as number, nativeCurrency as string, locale) }})
    </span>
  </span>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { formatMoney } from '../../utils/currency'

const props = defineProps<{
  amount: number | null
  currency: string
  nativeAmount?: number | null
  nativeCurrency?: string | null
}>()

const { t, locale } = useI18n()

const showNative = computed(() =>
  props.nativeCurrency != null
  && props.nativeCurrency !== props.currency
  && props.nativeAmount != null,
)
</script>

<style scoped>
.money-native { color: var(--ash-gray); font-size: var(--text-micro); margin-left: 0.35em; }
</style>
