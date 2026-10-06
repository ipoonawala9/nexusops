import type { FieldValues, Path, UseFormSetError } from 'react-hook-form'
import { ApiError } from './client'

export const GENERIC_ERROR = 'Something went wrong. Please try again.'

/**
 * The server's human-readable detail for a failed call, or a generic message (never a stack trace). Field messages
 * (`errors[]`) are appended: callers use this when no form field showed them (e.g. "Request validation failed." for a
 * role that was deleted meanwhile would otherwise hide "Unknown role.").
 */
export function problemMessage(error: unknown, fallback: string = GENERIC_ERROR): string {
  if (!(error instanceof ApiError)) return fallback
  const parts = [error.problem.detail, ...(error.problem.errors ?? []).map((e) => e.message)]
  const text = parts.filter((part): part is string => Boolean(part)).join(' ')
  return text || fallback
}

/**
 * Puts server field errors (problem `errors[]`) on the matching form fields and focuses the first one (a disabled
 * submit button would otherwise drop focus to the document). Unknown fields are ignored.
 * Returns true when at least one message was applied, so callers can skip a duplicate form-level message.
 */
export function applyFieldErrors<T extends FieldValues>(
  error: unknown,
  setError: UseFormSetError<T>,
  fields: readonly Path<T>[],
): boolean {
  if (!(error instanceof ApiError) || !error.problem.errors?.length) return false
  let applied = false
  for (const { field, message } of error.problem.errors) {
    const match = fields.find((known) => known === field)
    if (match) {
      setError(match, { type: 'server', message }, { shouldFocus: !applied })
      applied = true
    }
  }
  return applied
}
