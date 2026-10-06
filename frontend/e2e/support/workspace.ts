import { expect, type Page } from '@playwright/test'
import { tokenFromEmail } from './mailpit'

export const PASSWORD = 'e2e correct horse battery'

export interface Workspace {
  name: string
  slug: string
  email: string
}

export function newWorkspace(prefix: string): Workspace {
  const stamp = `${Date.now().toString(36)}${Math.floor(Math.random() * 1e4).toString(36)}`
  return {
    name: `E2E ${prefix} ${stamp}`,
    slug: `e2e-${prefix}-${stamp}`,
    email: `owner-${stamp}@e2e.test`,
  }
}

export async function signUpAndVerify(page: Page, ws: Workspace): Promise<void> {
  await page.goto('/signup')
  await page.getByLabel('Workspace name').fill(ws.name)
  await page.getByLabel('Workspace URL').fill(ws.slug)
  await page.getByLabel('First name').fill('Ada')
  await page.getByLabel('Last name').fill('Owner')
  await page.getByLabel('Work email').fill(ws.email)
  await page.getByLabel('Password').fill(PASSWORD)
  await page.getByRole('button', { name: 'Create workspace' }).click()
  await expect(page.getByRole('heading', { name: 'Check your email' })).toBeVisible()
  const token = await tokenFromEmail(ws.email, '/verify-email')
  await page.goto(`/verify-email?token=${encodeURIComponent(token)}`)
  await expect(page.getByRole('heading', { name: 'Email verified' })).toBeVisible()
}

export async function signIn(
  page: Page,
  ws: Pick<Workspace, 'slug'>,
  email: string,
  password = PASSWORD,
) {
  await page.goto('/login')
  await page.getByLabel('Workspace URL').fill(ws.slug)
  await page.getByLabel('Email').fill(email)
  await page.getByLabel('Password').fill(password)
  await page.getByRole('button', { name: 'Sign in' }).click()
}
