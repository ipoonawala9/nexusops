import { useState } from 'react'
import { ApiError } from '@/lib/api/client'
import type { DuplicateCandidate } from '@/lib/api/types'

/** The candidates of a 409 duplicate problem (ADR-0008), or null for any other error. */
export function duplicatesOf(error: unknown): DuplicateCandidate[] | null {
  if (!(error instanceof ApiError) || error.status !== 409) return null
  const list = (error.problem as { duplicates?: unknown }).duplicates
  return Array.isArray(list) && list.length ? (list as DuplicateCandidate[]) : null
}

export const REASON_REQUIRED = 'Give a reason, or open the existing record instead.'

/**
 * Create/edit dialogs: after a duplicate conflict the user must either open an existing record or give a reason,
 * which is sent as duplicateReason on the next submit.
 */
export function useDuplicateGuard() {
  const [candidates, setCandidates] = useState<DuplicateCandidate[] | null>(null)
  const [reason, setReason] = useState('')
  const [reasonError, setReasonError] = useState<string | undefined>()

  /** The duplicateReason to send (null before any conflict), or false when the reason is still missing. */
  function reasonToSend(): string | null | false {
    if (!candidates) return null
    if (!reason.trim()) {
      setReasonError(REASON_REQUIRED)
      return false
    }
    setReasonError(undefined)
    return reason.trim()
  }

  /** True when the error was a duplicate conflict (now shown); false for anything else. */
  function handleError(error: unknown): boolean {
    const found = duplicatesOf(error)
    if (!found) return false
    setCandidates(found)
    return true
  }

  return { candidates, reason, setReason, reasonError, reasonToSend, handleError }
}
