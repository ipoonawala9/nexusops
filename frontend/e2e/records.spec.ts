import { expect, test } from '@playwright/test'
import { newWorkspace, signIn, signUpAndVerify } from './support/workspace'

test('one record per customer, with its activity, tasks and files', async ({ page }) => {
  const ws = newWorkspace('records')
  await signUpAndVerify(page, ws)
  await signIn(page, ws, ws.email)
  await expect(page.getByRole('heading', { name: 'Welcome, Ada' })).toBeVisible()
  const nav = page.getByRole('navigation', { name: 'Workspace' })

  // An organization, marked as a customer.
  await nav.getByRole('link', { name: 'Directory' }).click()
  await page.getByRole('button', { name: 'New organization' }).click()
  let dialog = page.getByRole('dialog')
  await dialog.getByLabel('Name').fill('Acme Robotics')
  await dialog.getByLabel('Domain').fill('https://www.Acme-Robotics.test/about')
  await dialog.getByRole('button', { name: 'Create organization' }).click()
  await expect(page.getByRole('heading', { name: 'Acme Robotics' })).toBeVisible()
  await expect(page.getByText('acme-robotics.test', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'Mark as customer' }).click()
  await expect(page.getByRole('button', { name: 'End customer role' })).toBeVisible()
  const acmeUrl = page.url()

  // A person who works there.
  await nav.getByRole('link', { name: 'Directory' }).click()
  await page.getByRole('button', { name: 'New person' }).click()
  dialog = page.getByRole('dialog')
  await dialog.getByLabel('First name').fill('Grace')
  await dialog.getByLabel('Last name').fill('Hopper')
  await dialog.getByLabel('Organization').selectOption({ label: 'Acme Robotics' })
  await dialog.getByLabel('Email').fill('grace@acme-robotics.test')
  await dialog.getByRole('button', { name: 'Create person' }).click()
  await expect(page.getByRole('heading', { name: 'Grace Hopper' })).toBeVisible()

  // The same email again is a probable duplicate: refused until a reason is given.
  await nav.getByRole('link', { name: 'Directory' }).click()
  await page.getByRole('button', { name: 'New person' }).click()
  dialog = page.getByRole('dialog')
  await dialog.getByLabel('First name').fill('Robotics team')
  await dialog.getByLabel('Email').fill('GRACE@acme-robotics.test')
  await dialog.getByRole('button', { name: 'Create person' }).click()
  await expect(dialog.getByText('This looks like a record that already exists.')).toBeVisible()
  await expect(dialog.getByRole('link', { name: 'Grace Hopper' })).toBeVisible()
  await dialog.getByLabel('Why keep a separate record?').fill('Shared inbox for the robotics team')
  await dialog.getByRole('button', { name: 'Create anyway' }).click()
  await expect(page.getByRole('heading', { name: 'Robotics team' })).toBeVisible()
  await expect(page.getByText('Shared inbox for the robotics team')).toBeVisible()

  // Work on the organization: a note, a task and a file.
  await page.goto(acmeUrl)
  const activity = page.getByRole('region', { name: 'Activity' })
  await activity.getByLabel('Summary').fill('Kick-off call booked')
  await activity.getByLabel('Details').fill('Thursday 10:00 with Grace')
  await activity.getByRole('button', { name: 'Log activity' }).click()
  await expect(activity.getByText('Kick-off call booked')).toBeVisible()

  const tasks = page.getByRole('region', { name: 'Tasks' })
  await tasks.getByRole('button', { name: 'New task' }).click()
  dialog = page.getByRole('dialog')
  await dialog.getByLabel('Title').fill('Send the proposal')
  await dialog.getByLabel('Due date').fill('2030-01-15')
  await dialog.getByLabel('Assignee').selectOption({ label: 'Ada Owner' })
  await dialog.getByRole('button', { name: 'Create task' }).click()
  await expect(tasks.getByText('Send the proposal')).toBeVisible()

  const documents = page.getByRole('region', { name: 'Documents' })
  await documents.getByLabel('Upload file').setInputFiles({
    name: 'proposal.txt',
    mimeType: 'text/plain',
    buffer: Buffer.from('Proposal v1'),
  })
  const file = documents.getByRole('button', { name: 'Download proposal.txt' })
  await expect(file).toBeVisible()
  const downloading = page.waitForEvent('download')
  await file.click()
  expect((await downloading).suggestedFilename()).toBe('proposal.txt')

  // The task is in "My tasks" and is completed there.
  await nav.getByRole('link', { name: 'Tasks' }).click()
  await expect(page.getByRole('link', { name: 'Acme Robotics' })).toBeVisible()
  await page.getByLabel('Status of Send the proposal').selectOption('DONE')
  await expect(page.getByText('No tasks here.')).toBeVisible()

  // Products: SKUs are unique, ignoring case.
  await nav.getByRole('link', { name: 'Products' }).click()
  await page.getByRole('button', { name: 'New product' }).click()
  dialog = page.getByRole('dialog')
  await dialog.getByLabel('SKU').fill('ROBO-1')
  await dialog.getByLabel('Name').fill('Robot arm')
  await dialog.getByLabel('List price').fill('1999.99')
  await dialog.getByRole('button', { name: 'Create product' }).click()
  await expect(page.getByRole('heading', { name: 'Robot arm' })).toBeVisible()
  await nav.getByRole('link', { name: 'Products' }).click()
  await page.getByRole('button', { name: 'New product' }).click()
  dialog = page.getByRole('dialog')
  await dialog.getByLabel('SKU').fill('robo-1')
  await dialog.getByLabel('Name').fill('Copy')
  await dialog.getByRole('button', { name: 'Create product' }).click()
  await expect(dialog.getByText('Another product already uses this SKU.')).toBeVisible()
  await dialog.getByRole('button', { name: 'Cancel' }).click()

  // The audit log recorded every step.
  await page.goto('/app/audit')
  for (const action of [
    'OrganizationCreated',
    'PartyRoleChanged',
    'PersonCreated',
    'ActivityLogged',
    'TaskCreated',
    'DocumentUploaded',
    'TaskStatusChanged',
    'ProductCreated',
  ]) {
    await expect(page.getByText(action, { exact: true }).first()).toBeVisible()
  }
})
