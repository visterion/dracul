export type BudgetLevel = 'ok' | 'warn' | 'over'

/** warn at >= 80% of cap, over at >= 100%; no cap means no warning. */
export function budgetLevel(usedUsd: number, capUsd: number): BudgetLevel {
  if (!capUsd || capUsd <= 0) return 'ok'
  const ratio = usedUsd / capUsd
  if (ratio >= 1) return 'over'
  if (ratio >= 0.8) return 'warn'
  return 'ok'
}
