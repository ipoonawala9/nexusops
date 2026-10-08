import { z } from 'zod'
import { requiredText } from '@/features/auth/schemas'

/** Amount typed as text: '' or a non-negative number with at most 4 decimals. */
export const moneySchema = z
  .string()
  .trim()
  .refine((v) => v === '' || /^-?\d+(\.\d+)?$/.test(v), 'Enter a number like 1200.50.')
  .refine((v) => !v.startsWith('-'), 'Enter an amount of 0 or more.')
  .refine((v) => v === '' || !/\.\d{5,}$/.test(v), 'Use at most 4 decimal places.')

export const currencySchema = z
  .string()
  .trim()
  .refine((v) => v === '' || /^[A-Za-z]{3}$/.test(v), 'Use a 3-letter currency code like USD.')

/** Mirrors LeadService: the server stays the authority. */
export const leadSchema = z
  .object({
    firstName: z.string().trim().max(80, 'Use at most 80 characters.'),
    lastName: z.string().trim().max(80, 'Use at most 80 characters.'),
    companyName: z.string().trim().max(200, 'Use at most 200 characters.'),
    jobTitle: z.string().trim().max(100, 'Use at most 100 characters.'),
    email: z
      .string()
      .trim()
      .refine(
        (v) => v === '' || /^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(v),
        'Enter a valid email address.',
      ),
    phone: z.string().trim().max(40, 'Use at most 40 characters.'),
    source: z.enum([
      'WEBSITE',
      'REFERRAL',
      'WALK_IN',
      'PHONE',
      'EMAIL',
      'SOCIAL',
      'EVENT',
      'OTHER',
    ]),
    ownerId: z.string(),
    estimatedValue: moneySchema,
    currency: currencySchema,
    description: z.string().max(5000, 'Use at most 5000 characters.'),
  })
  .refine((v) => v.firstName || v.lastName || v.companyName, {
    path: ['lastName'],
    message: 'Enter a name or a company.',
  })
export type LeadValues = z.infer<typeof leadSchema>

export const reasonSchema = z.object({ reason: requiredText(500) })

export const opportunitySchema = z.object({
  name: requiredText(200),
  accountId: z.string().min(1, 'Choose an account.'),
  contactId: z.string(),
  stageId: z.string(),
  amount: moneySchema,
  currency: currencySchema,
  expectedCloseOn: z.string(),
  ownerId: z.string(),
  description: z.string().max(5000, 'Use at most 5000 characters.'),
})
export type OpportunityValues = z.infer<typeof opportunitySchema>
