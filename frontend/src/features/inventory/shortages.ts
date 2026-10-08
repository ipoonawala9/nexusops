import { ApiError } from '@/lib/api/client'
import type { Shortage } from '@/lib/api/types'
import { formatQuantity } from './quantity'

/** "Not enough stock: W-1 needs 50, 10 available; G-1 needs 2, 0 available." — or null for other errors. */
export function shortageText(error: unknown): string | null {
  if (!(error instanceof ApiError)) return null
  const shortages = (error.problem as { shortages?: Shortage[] }).shortages
  if (!shortages?.length) return null
  return `Not enough stock: ${shortages
    .map(
      (s) =>
        `${s.sku} needs ${formatQuantity(s.requested)}, ${formatQuantity(s.available)} available`,
    )
    .join('; ')}.`
}
