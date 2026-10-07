import { z } from 'zod'
import { MESSAGES, requiredText } from '@/features/auth/schemas'

/** Mirrors PartyService's limits; the server stays the authority (and normalizes domains, websites, phones). */
const EMAIL = /^[^@\s]+@[^@\s]+\.[^@\s]+$/
const optionalText = (max: number) => z.string().trim().max(max, `Use at most ${max} characters.`)
const optionalEmail = optionalText(254).refine((value) => value === '' || EMAIL.test(value), MESSAGES.email)

export const personSchema = z.object({
  firstName: requiredText(80),
  lastName: optionalText(80),
  jobTitle: optionalText(100),
  organizationId: z.string(),
  email: optionalEmail,
  phone: optionalText(40),
})
export type PersonValues = z.infer<typeof personSchema>

export const organizationSchema = z.object({
  name: requiredText(200),
  domain: optionalText(253),
  website: optionalText(255),
  email: optionalEmail,
  phone: optionalText(40),
})
export type OrganizationValues = z.infer<typeof organizationSchema>

/** Empty optional fields go to the server as null. */
export function blankToNull<T extends Record<string, string>>(values: T): { [K in keyof T]: string | null } {
  return Object.fromEntries(
    Object.entries(values).map(([key, value]) => [key, value.trim() === '' ? null : value.trim()]),
  ) as { [K in keyof T]: string | null }
}
