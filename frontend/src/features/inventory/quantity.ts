import { z } from 'zod'

const numberFormat = new Intl.NumberFormat(undefined, { maximumFractionDigits: 4 })

/** 12, 2.5 kg — never trailing zeros. */
export function formatQuantity(value: number, unit?: string): string {
  const text = numberFormat.format(value)
  return unit ? `${text} ${unit}` : text
}

/** A quantity typed as text: 0 or more, at most 4 decimals (the server's numeric(19,4)). */
export const quantitySchema = z
  .string()
  .trim()
  .refine((v) => /^\d+(\.\d+)?$/.test(v), 'Enter a number like 12 or 2.5.')
  .refine((v) => !/\.\d{5,}$/.test(v), 'Use at most 4 decimal places.')

export const positiveQuantitySchema = quantitySchema.refine(
  (v) => Number(v) > 0,
  'Enter a quantity greater than 0.',
)
