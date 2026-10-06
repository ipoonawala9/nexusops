import { expect, test } from '@playwright/test'
import { totp } from './support/totp'

test('the TOTP helper matches RFC 6238', () => {
  expect(totp('GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ', 59_000)).toBe('287082')
  expect(totp('GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ', 1_111_111_109_000)).toBe('081804')
})
