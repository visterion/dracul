<template>
  <span class="money">
    <template v-if="originalPair">{{ originalPair }}</template>
    <template v-else>
      <span class="money-primary">{{ formatMoney(amount, currency, locale) }}</span>
      <span v-if="showNative" class="money-native">
        ({{ t('common.origPrice') }} {{ formatMoney(nativeAmount as number, nativeCurrency as string, locale) }})
      </span>
    </template>
  </span>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { formatMoney } from '../../utils/currency'
import { formatMoneyPair } from '../../utils/format'

const props = defineProps<{
  amount: number | null
  currency: string
  nativeAmount?: number | null
  nativeCurrency?: string | null
  originalPrimary?: boolean
}>()

const { t, locale } = useI18n()

const showNative = computed(() =>
  props.nativeCurrency != null
  && props.nativeCurrency !== props.currency
  && props.nativeAmount != null,
)

// C2: for foreign-listed market prices, render the original currency first and
// the converted display value in parens — "1.247,50 $ (1.147,70 €)". Opt-in so
// portfolio holdings (entry + current) keep their display-currency-primary shape.
const originalPair = computed<string | null>(() => {
  if (!props.originalPrimary || props.amount == null) return null
  if (props.nativeAmount == null || props.nativeCurrency == null) return null
  if (props.nativeCurrency === props.currency) return null
  return formatMoneyPair(
    props.nativeAmount,
    props.nativeCurrency,
    { value: props.amount, currency: props.currency },
  )
})
</script>

<style scoped>
.money-native { color: var(--ash-gray); font-size: var(--text-micro); margin-left: 0.35em; }
</style>
