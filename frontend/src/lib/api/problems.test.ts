import { describe, expect, it, vi } from 'vitest'
import { ApiError } from './client'
import { applyFieldErrors, GENERIC_ERROR, problemMessage } from './problems'

describe('problems', () => {
  it('uses the server detail, else a generic message', () => {
    expect(problemMessage(new ApiError({ status: 409, detail: 'Taken.' }))).toBe('Taken.')
    expect(problemMessage(new ApiError({ status: 500 }))).toBe(GENERIC_ERROR)
    expect(problemMessage(new TypeError('Failed to fetch'))).toBe(GENERIC_ERROR)
    expect(problemMessage(new ApiError({ status: 500 }), 'Could not save.')).toBe('Could not save.')
  })

  it('maps server field errors onto known form fields only', () => {
    const setError = vi.fn()
    const error = new ApiError({
      status: 400,
      errors: [
        { field: 'slug', message: 'This workspace URL is already taken.' },
        { field: 'unknown', message: 'ignored' },
      ],
    })
    expect(applyFieldErrors(error, setError, ['slug', 'email'] as const)).toBe(true)
    expect(setError).toHaveBeenCalledTimes(1)
    expect(setError).toHaveBeenCalledWith('slug', {
      type: 'server',
      message: 'This workspace URL is already taken.',
    })
    expect(applyFieldErrors(new ApiError({ status: 401 }), setError, ['slug'] as const)).toBe(false)
  })
})
