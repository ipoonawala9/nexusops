import { expect, test } from '@playwright/test'
import { newWorkspace, signIn, signUpAndVerify } from './support/workspace'

test('a lead becomes a customer without a duplicate, and the deal is won', async ({ page }) => {
  const ws = newWorkspace('crm')
  await signUpAndVerify(page, ws)
  await signIn(page, ws, ws.email)
  await expect(page.getByRole('heading', { name: 'Welcome, Ada' })).toBeVisible()
  const nav = page.getByRole('navigation', { name: 'Workspace' })

  // Enable CRM.
  await page.goto('/app/settings/modules')
  await page.getByRole('switch', { name: 'CRM' }).click()
  await expect(page.getByText('CRM enabled.')).toBeVisible()

  // An organization that already exists under a slightly different name.
  await nav.getByRole('link', { name: 'Directory' }).click()
  await page.getByRole('button', { name: 'New organization' }).click()
  let dialog = page.getByRole('dialog')
  await dialog.getByLabel('Name').fill('Acme Robotics Ltd')
  await dialog.getByRole('button', { name: 'Create organization' }).click()
  await expect(page.getByRole('heading', { name: 'Acme Robotics Ltd' })).toBeVisible()

  // A lead, worked and qualified.
  await nav.getByRole('link', { name: 'CRM' }).click()
  await page.getByRole('navigation', { name: 'CRM' }).getByRole('link', { name: 'Leads' }).click()
  await page.getByRole('button', { name: 'New lead' }).click()
  dialog = page.getByRole('dialog')
  await dialog.getByLabel('First name').fill('Grace')
  await dialog.getByLabel('Last name').fill('Hopper')
  await dialog.getByLabel('Company').fill('Acme Robotics')
  await dialog.getByLabel('Email').fill('grace@acme-robotics.test')
  await dialog.getByLabel('Estimated value').fill('5000')
  await dialog.getByRole('button', { name: 'Create lead' }).click()
  await expect(page.getByRole('heading', { name: 'Grace Hopper' })).toBeVisible()
  const activity = page.getByRole('region', { name: 'Activity' })
  await activity.getByLabel('Summary').fill('Intro call with Grace')
  await activity.getByRole('button', { name: 'Log activity' }).click()
  await expect(activity.getByText('Intro call with Grace')).toBeVisible()
  await page.getByRole('button', { name: 'Mark qualified' }).click()
  await expect(page.getByText('Qualified', { exact: true })).toBeVisible()

  // Converting proposes a new "Acme Robotics": refused as a probable duplicate, then linked.
  await page.getByRole('button', { name: 'Convert' }).click()
  dialog = page.getByRole('dialog')
  await dialog.getByRole('button', { name: 'Convert lead' }).click()
  await expect(dialog.getByText('This looks like a record that already exists.')).toBeVisible()
  await dialog.getByRole('button', { name: 'Use Acme Robotics Ltd' }).click()
  await dialog.getByRole('button', { name: 'Convert lead' }).click()
  await expect(page.getByText(/This lead was converted/)).toBeVisible()

  // The opportunity moves to Won.
  await page.getByRole('link', { name: 'Open the opportunity' }).click()
  await expect(page.getByRole('heading', { name: 'Acme Robotics' })).toBeVisible()
  await page.getByLabel('Move Acme Robotics to').selectOption({ label: 'Proposal' })
  await expect(page.getByText('Moved to Proposal.')).toBeVisible()
  await page.getByLabel('Move Acme Robotics to').selectOption({ label: 'Won' })
  await expect(page.getByText('Moved to Won.')).toBeVisible()

  // The account is a customer with the won value; its 360 timeline includes the lead's note.
  await page
    .getByRole('navigation', { name: 'CRM' })
    .getByRole('link', { name: 'Customers' })
    .click()
  const row = page.getByRole('row', { name: /Acme Robotics Ltd/ })
  await expect(row).toContainText('$5,000.00')
  await row.getByRole('link', { name: 'Acme Robotics Ltd' }).click()
  await expect(page.getByRole('heading', { name: 'Acme Robotics Ltd' })).toBeVisible()
  await expect(
    page.getByRole('region', { name: 'Activity' }).getByText('Intro call with Grace'),
  ).toBeVisible()

  // Header search finds the lead.
  await page.getByLabel('Search records').fill('Grace')
  const results = page.getByRole('list', { name: 'Search results' })
  await expect(results.getByRole('link', { name: /Grace Hopper.*Lead/ })).toBeVisible()
})
