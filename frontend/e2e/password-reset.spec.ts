import { expect, test } from '@playwright/test'
import { tokenFromEmail } from './support/mailpit'
import { newWorkspace, PASSWORD, signIn, signUpAndVerify } from './support/workspace'

const NEW_PASSWORD = 'e2e a brand new passphrase'

test('a user who forgot their password resets it from an emailed link and is signed out everywhere', async ({
  page,
  browser,
}) => {
  const ws = newWorkspace('reset')
  await signUpAndVerify(page, ws)

  // An existing session on one device…
  await signIn(page, ws, ws.email)
  await expect(page.getByRole('heading', { name: 'Welcome, Ada' })).toBeVisible()

  // …and the reset on another.
  const other = await browser.newContext()
  const phone = await other.newPage()
  await phone.goto('/login')
  await phone.getByRole('link', { name: 'Forgot password?' }).click()
  await expect(phone.getByRole('heading', { name: 'Forgot your password?' })).toBeVisible()
  await phone.getByLabel('Workspace URL').fill(ws.slug)
  await phone.getByLabel('Email').fill(ws.email)
  await phone.getByRole('button', { name: 'Send reset link' }).click()
  await expect(phone.getByRole('heading', { name: 'Check your email' })).toBeVisible()
  await expect(phone.getByText("If an account matches, we've sent a link")).toBeVisible()

  const token = await tokenFromEmail(ws.email, '/reset-password')
  await phone.goto(`/reset-password?token=${encodeURIComponent(token)}`)
  await phone.getByLabel('New password', { exact: true }).fill(NEW_PASSWORD)
  await phone.getByLabel('Repeat password', { exact: true }).fill(`${NEW_PASSWORD} typo`)
  await phone.getByRole('button', { name: 'Set new password' }).click()
  await expect(phone.getByText("Passwords don't match.")).toBeVisible()
  await phone.getByLabel('Repeat password', { exact: true }).fill(NEW_PASSWORD)
  await phone.getByRole('button', { name: 'Set new password' }).click()
  await expect(phone.getByRole('heading', { name: 'Password changed' })).toBeVisible()

  // The link works once.
  await phone.goto(`/reset-password?token=${encodeURIComponent(token)}`)
  await phone.getByLabel('New password', { exact: true }).fill(PASSWORD)
  await phone.getByLabel('Repeat password', { exact: true }).fill(PASSWORD)
  await phone.getByRole('button', { name: 'Set new password' }).click()
  await expect(phone.getByText('This reset link is invalid or has expired.')).toBeVisible()

  // The first device lost its session.
  await page.reload()
  await expect(page.getByRole('heading', { name: 'Sign in' })).toBeVisible()

  // The old password is dead; the new one works.
  await signIn(phone, ws, ws.email)
  await expect(phone.getByText('Invalid workspace, email or password.')).toBeVisible()
  await signIn(phone, ws, ws.email, NEW_PASSWORD)
  await expect(phone.getByRole('heading', { name: 'Welcome, Ada' })).toBeVisible()
  await other.close()
})
