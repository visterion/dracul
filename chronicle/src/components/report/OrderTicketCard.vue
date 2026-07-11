<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import type { OrderTicket } from '../../api/types'
import { formatMoney, formatNumber } from '../../utils/format'

defineProps<{ ticket: OrderTicket }>()
const { t } = useI18n()
const fmt = (v: number | null) => (v == null ? '—' : formatMoney(v, 'USD'))
// Whole-share positions render "10"; fractional ones keep up to 4 decimals
// (share_count is NUMERIC(12,4)) instead of formatNumber's default of
// rounding to an integer, which would floor a 0.24-share ticket to "0".
const fmtShares = (v: number) => formatNumber(v, Number.isInteger(v) ? 0 : 4)
</script>

<template>
  <div class="ticket" :class="`ticket--${ticket.side.toLowerCase()}`" data-testid="order-ticket">
    <div class="ticket__head">
      <span class="ticket__side">{{ ticket.side }}</span>
      <span class="ticket__symbol mono">{{ ticket.symbol }}</span>
    </div>
    <dl class="ticket__grid">
      <dt>{{ t('report.ticket.shares') }}</dt><dd class="mono">{{ fmtShares(ticket.shares) }}</dd>
      <dt>{{ t('report.ticket.stop') }}</dt><dd class="mono">{{ fmt(ticket.stop) }}</dd>
      <dt>{{ t('report.ticket.target') }}</dt><dd class="mono">{{ fmt(ticket.target) }}</dd>
    </dl>
  </div>
</template>

<style scoped>
.ticket {
  background: var(--crypt-black-elevated);
  border: var(--hairline);
  border-radius: 4px;
  padding: var(--space-3) var(--space-4);
}
.ticket__head {
  display: flex;
  gap: var(--space-2);
  align-items: baseline;
  margin-bottom: var(--space-2);
}
.ticket__side {
  font-family: var(--font-display);
  font-size: var(--text-body-sm);
  letter-spacing: 0.04em;
  text-transform: uppercase;
}
.ticket--sell .ticket__side { color: var(--blood-crimson-bright); }
.ticket--trim .ticket__side { color: var(--cathedral-gold-bright); }
.ticket--hold .ticket__side { color: var(--ash-gray-light); }
.ticket__symbol {
  font-size: var(--text-body-sm);
  color: var(--bone-ivory);
}
.ticket__grid {
  display: grid;
  grid-template-columns: auto 1fr;
  gap: var(--space-1) var(--space-3);
  margin: 0;
}
.ticket__grid dt {
  color: var(--ash-gray-light);
  font-size: var(--text-micro);
  text-transform: uppercase;
  letter-spacing: 0.08em;
  align-self: center;
}
.ticket__grid dd {
  margin: 0;
  text-align: right;
  font-size: var(--text-body-sm);
  color: var(--bone-ivory-dim);
}
</style>
