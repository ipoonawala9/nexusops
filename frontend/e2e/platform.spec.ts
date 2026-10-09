import { expect, test } from '@playwright/test'
import { totp } from './support/totp'
import { newWorkspace, signIn, signUpAndVerify } from './support/workspace'

const OPERATOR = {
  email: 'e2e-ops@nexusops.test',
  password: 'e2e platform passphrase 42',
  secret: 'JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP',
}

test('a platform admin suspends and reactivates a workspace', async ({
  page,
  browser,
}, testInfo) => {
  const ws = newWorkspace('susp')
  await signUpAndVerify(page, ws)
  await signIn(page, ws, ws.email)
  await expect(page.getByRole('heading', { name: 'Welcome, Ada' })).toBeVisible()

  const opsContext = await browser.newContext()
  const ops = await opsContext.newPage()
  await ops.goto('/platform/login')
  await ops.getByLabel('Email').fill(OPERATOR.email)
  await ops.getByLabel('Password', { exact: true }).fill(OPERATOR.password)
  // The server rejects a code whose step isn't after the last one used (replay). A CI retry may sign in again within
  // the same 30 s step, so a retry uses the next step's code (still inside the server's ±1 step window).
  const at = Date.now() + (testInfo.retry > 0 ? 30_000 : 0)
  await ops.getByLabel('Authenticator code').fill(totp(OPERATOR.secret, at))
  await ops.getByRole('button', { name: 'Sign in' }).click()
  await expect(ops.getByRole('heading', { name: 'Workspaces' })).toBeVisible()

  await ops.getByLabel('Search workspaces').fill(ws.slug)
  await ops.getByRole('button', { name: 'Search' }).click()
  await ops.getByRole('button', { name: `Suspend ${ws.name}` }).click()
  await ops.getByRole('dialog').getByLabel('Reason').fill('E2E suspension check')
  await ops.getByRole('button', { name: 'Suspend workspace' }).click()
  await expect(ops.getByText(`${ws.name} suspended.`)).toBeVisible()

  // The owner's next action that reaches the API names the reason (lists refetch whenever their page mounts)...
  await page
    .getByRole('navigation', { name: 'Workspace' })
    .getByRole('link', { name: 'Settings' })
    .click()
  await page
    .getByRole('navigation', { name: 'Settings' })
    .getByRole('link', { name: 'Roles' })
    .click()
  await expect(page).toHaveURL(/\/app\/settings\/roles$/)
  await expect(page.getByRole('alert').filter({ hasText: 'Workspace suspended.' })).toBeVisible()

  // ...and they are out: a reload can't restore the session, and signing in names the reason.
  await page.reload()
  await expect(page.getByRole('heading', { name: 'Sign in' })).toBeVisible()
  await signIn(page, ws, ws.email)
  await expect(page.getByText('Workspace suspended.')).toBeVisible()

  await ops.getByRole('button', { name: `Reactivate ${ws.name}` }).click()
  await ops.getByRole('dialog').getByLabel('Reason').fill('E2E done')
  await ops.getByRole('button', { name: 'Reactivate workspace' }).click()
  await expect(ops.getByText(`${ws.name} reactivated.`)).toBeVisible()
  await opsContext.close()

  await page.getByRole('button', { name: 'Sign in' }).click()
  await expect(page.getByRole('heading', { name: 'Welcome, Ada' })).toBeVisible()
})
