import { z } from 'zod'

export const MESSAGES = {
  required: 'Required.',
  email: 'Enter a valid email address.',
  password: 'Use at least 12 characters.',
  passwordsDiffer: "The passwords don't match.",
} as const

/** Same shape the server checks (Emails.SHAPE); the server stays the authority. */
const EMAIL = /^[^@\s]+@[^@\s]+\.[^@\s]+$/

export const requiredText = (max: number) =>
  z.string().trim().min(1, MESSAGES.required).max(max, `Use at most ${max} characters.`)

export const emailField = z
  .string()
  .trim()
  .min(1, MESSAGES.required)
  .max(254, 'Use at most 254 characters.')
  .regex(EMAIL, MESSAGES.email)

export const newPasswordField = z
  .string()
  .min(12, MESSAGES.password)
  .max(128, 'Use at most 128 characters.')

export const slugField = z
  .string()
  .trim()
  .min(3, 'Use 3 to 40 characters.')
  .max(40, 'Use 3 to 40 characters.')
  .regex(/^[a-z0-9]+(-[a-z0-9]+)*$/, 'Use lowercase letters, numbers and single hyphens.')

/** "Acme Trading Co." → "acme-trading-co" (a suggestion; the user can edit it). */
export function slugify(name: string): string {
  return name
    .toLowerCase()
    .normalize('NFKD')
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 40)
    .replace(/-+$/g, '')
}
