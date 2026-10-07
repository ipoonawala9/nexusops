import { z } from 'zod'
import { requiredText } from '@/features/auth/schemas'

/** Mirrors ProductService; the server stays the authority. Price is text in the form, a number on the wire. */
export const productSchema = z.object({
  sku: requiredText(64).regex(
    /^[A-Za-z0-9][A-Za-z0-9._/-]*$/,
    'Use letters, digits and . _ / - only.',
  ),
  name: requiredText(200),
  description: z.string().max(2000, 'Use at most 2000 characters.'),
  kind: z.enum(['GOODS', 'SERVICE']),
  unit: z.string().trim().max(20, 'Use at most 20 characters.'),
  listPrice: z
    .string()
    .trim()
    .refine(
      (v) => v === '' || /^\d+(\.\d{1,4})?$/.test(v) || /^-/.test(v),
      'Enter a number like 19.99.',
    )
    .refine((v) => !v.startsWith('-'), 'Enter a price of 0 or more.'),
  currency: z
    .string()
    .trim()
    .refine((v) => v === '' || /^[A-Za-z]{3}$/.test(v), 'Use a 3-letter currency code like USD.'),
})
export type ProductValues = z.infer<typeof productSchema>

export const KIND_LABELS = { GOODS: 'Goods', SERVICE: 'Service' } as const
