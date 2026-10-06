import type { FieldValues, Path, UseFormSetError } from 'react-hook-form'
import { ApiError } from './client'

export const GENERIC_ERROR = 'Something went wrong. Please try again.'

/** The server's human-readable detail for a failed call, or a generic message (never a stack trace). */
export function problemMessage(error: unknown, fallback: string = GENERIC_ERROR): string {
  if (error instanceof ApiError && error.problem.detail) {
    return error.problem.detail
  }
  return fallback
}

/**
 * Puts server field errors (problem `errors[]`) on the matching form fields. Unknown fields are ignored.
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
      setError(match, { type: 'server', message })
      applied = true
    }
  }
  return applied
}
