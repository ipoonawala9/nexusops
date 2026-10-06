import { expect, test } from '@playwright/test'
import { tokenFromEmail } from './support/mailpit'
import { newWorkspace, PASSWORD, signIn, signUpAndVerify } from './support/workspace'

test('an owner builds a team, and the audit log shows every step', async ({ page, browser }) => {
  const ws = newWorkspace('team')
  const invitee = `grace-${ws.slug}@e2e.test`

  await signUpAndVerify(page, ws)
  await signIn(page, ws, ws.email)
  await expect(page.getByRole('heading', { name: 'Welcome, Ada' })).toBeVisible()

  // A custom role that can see people.
  await page.goto('/app/settings/roles') // also proves a deep link restores the session from the refresh cookie
  await page.getByRole('button', { name: 'New role' }).click()
  await page.getByRole('dialog').getByLabel('Name').fill('Support agent')
  await page.getByRole('button', { name: 'Create role' }).click()
  await expect(page.getByRole('heading', { name: 'Support agent' })).toBeVisible()
  await page.getByRole('checkbox', { name: /View users/ }).check()
  await page.getByRole('button', { name: 'Save permissions' }).click()
  await expect(page.getByText('Permissions saved.')).toBeVisible()

  // Invite someone with it.
  await page
    .getByRole('navigation', { name: 'Settings' })
    .getByRole('link', { name: 'Users' })
    .click()
  await page.getByRole('button', { name: 'Invite people' }).click()
  const invite = page.getByRole('dialog')
  await invite.getByLabel('Email').fill(invitee)
  await invite.getByLabel('Role').selectOption({ label: 'Support agent' })
  await invite.getByRole('button', { name: 'Send invitation' }).click()
  await expect(page.getByText(`Invitation sent to ${invitee}.`)).toBeVisible()

  // The invitee accepts in their own browser and signs in.
  const token = await tokenFromEmail(invitee, '/invite/accept')
  const graceContext = await browser.newContext()
  const grace = await graceContext.newPage()
  await grace.goto(`/invite/accept?token=${encodeURIComponent(token)}`)
  await expect(grace.getByRole('heading', { name: `Join ${ws.name}` })).toBeVisible()
  await grace.getByLabel('First name').fill('Grace')
  await grace.getByLabel('Last name').fill('Hopper')
  await grace.getByLabel('Password', { exact: true }).fill(PASSWORD)
  await grace.getByLabel('Repeat password').fill(PASSWORD)
  await grace.getByRole('button', { name: `Join ${ws.name}` }).click()
  await expect(grace.getByRole('heading', { name: "You're in" })).toBeVisible()
  await grace.getByRole('link', { name: `Sign in to ${ws.name}` }).click()
  await grace.getByLabel('Password').fill(PASSWORD)
  await grace.getByRole('button', { name: 'Sign in' }).click()
  await expect(grace.getByRole('heading', { name: 'Welcome, Grace' })).toBeVisible()
  const graceNav = grace.getByRole('navigation', { name: 'Workspace' })
  await expect(graceNav.getByRole('link', { name: 'Settings' })).toBeVisible()
  await expect(graceNav.getByRole('link', { name: 'Audit' })).toHaveCount(0)

  // The owner gives Grace a second role that can read the audit log.
  await page
    .getByRole('navigation', { name: 'Settings' })
    .getByRole('link', { name: 'Roles' })
    .click()
  await page.getByRole('button', { name: 'New role' }).click()
  await page.getByRole('dialog').getByLabel('Name').fill('Auditor')
  await page.getByRole('button', { name: 'Create role' }).click()
  await page.getByRole('checkbox', { name: /View the audit log/ }).check()
  await page.getByRole('button', { name: 'Save permissions' }).click()
  await expect(page.getByText('Permissions saved.')).toBeVisible()
  await page
    .getByRole('navigation', { name: 'Settings' })
    .getByRole('link', { name: 'Users' })
    .click()
  // Grace joined in another browser; the list is cached for 30 s, so reload to see her.
  await page.reload()
  await page.getByRole('button', { name: 'Manage Grace Hopper' }).click()
  await page.getByRole('dialog').getByRole('checkbox', { name: 'Auditor' }).check()
  await page.getByRole('dialog').getByRole('button', { name: 'Save roles' }).click()
  await expect(page.getByText('Roles updated.')).toBeVisible()

  // Grace sees the new section after a reload (permissions are resolved per request).
  await grace.reload()
  await expect(graceNav.getByRole('link', { name: 'Audit' })).toBeVisible()
  await graceContext.close()

  // The audit trail shows the journey.
  await page.keyboard.press('Escape')
  await page
    .getByRole('navigation', { name: 'Workspace' })
    .getByRole('link', { name: 'Audit' })
    .click()
  await expect(page.getByRole('heading', { name: 'Audit log' })).toBeVisible()
  for (const action of [
    'RoleCreated',
    'RolePermissionsChanged',
    'InvitationCreated',
    'InvitationAccepted',
    'UserRolesChanged',
  ]) {
    await expect(page.getByRole('cell', { name: action }).first()).toBeVisible()
  }
})
