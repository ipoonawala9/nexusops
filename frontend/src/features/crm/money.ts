import { formatMoney } from '@/lib/format'
import type { MoneyTotal } from '@/lib/api/types'

/** "$1,200.00 · €50.00" — one figure per currency, never summed across currencies. */
export function formatTotals(totals: MoneyTotal[]): string {
  return totals.length ? totals.map((t) => formatMoney(t.amount, t.currency)).join(' · ') : '—'
}
