import { describe, expect, it } from 'vitest'
import { formatQuantity, positiveQuantitySchema, quantitySchema } from './quantity'

describe('quantities', () => {
  it('formats without trailing zeros and with the unit', () => {
    expect(formatQuantity(12)).toBe('12')
    expect(formatQuantity(2.5, 'kg')).toBe('2.5 kg')
    expect(formatQuantity(0.1234)).toBe('0.1234')
  })

  it('accepts counts of 0 or more with at most 4 decimals', () => {
    expect(quantitySchema.safeParse('0').success).toBe(true)
    expect(quantitySchema.safeParse('2.5').success).toBe(true)
    expect(quantitySchema.safeParse('').error?.issues[0].message).toBe(
      'Enter a number like 12 or 2.5.',
    )
    expect(quantitySchema.safeParse('-1').error?.issues[0].message).toBe(
      'Enter a number like 12 or 2.5.',
    )
    expect(quantitySchema.safeParse('1.23456').error?.issues[0].message).toBe(
      'Use at most 4 decimal places.',
    )
  })

  it('needs more than 0 for movements and order lines', () => {
    expect(positiveQuantitySchema.safeParse('0').error?.issues[0].message).toBe(
      'Enter a quantity greater than 0.',
    )
    expect(positiveQuantitySchema.safeParse('0.5').success).toBe(true)
  })
})
