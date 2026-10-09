import { expect, test, type Page } from '@playwright/test'
import { emailText, tokenFromEmail } from './support/mailpit'
import { newWorkspace, PASSWORD, signIn, signUpAndVerify } from './support/workspace'

function helpdeskTab(page: Page, name: string) {
  return page.getByRole('navigation', { name: 'HelpDesk' }).getByRole('link', { name })
}

test('a customer call becomes a ticket with its context, is answered within SLA, waits, and is resolved', async ({
  page,
  browser,
}) => {
  const ws = newWorkspace('hd')
  const agentEmail = `ravi-${ws.slug}@e2e.test`
  const customerEmail = `meera-${ws.slug}@e2e.test`
  await signUpAndVerify(page, ws)
  await signIn(page, ws, ws.email)
  await expect(page.getByRole('heading', { name: 'Welcome, Ada' })).toBeVisible()
  const nav = page.getByRole('navigation', { name: 'Workspace' })

  // Enable HelpDesk and Inventory.
  await page.goto('/app/settings/modules')
  await page.getByRole('switch', { name: 'HelpDesk' }).click()
  await expect(page.getByText('HelpDesk enabled.')).toBeVisible()
  await page.getByRole('switch', { name: 'Inventory' }).click()
  await expect(page.getByText('Inventory enabled.')).toBeVisible()

  // An agent joins the team.
  await page
    .getByRole('navigation', { name: 'Settings' })
    .getByRole('link', { name: 'Users' })
    .click()
  await page.getByRole('button', { name: 'Invite people' }).click()
  let dialog = page.getByRole('dialog')
  await dialog.getByLabel('Email').fill(agentEmail)
  await dialog.getByLabel('Role').selectOption({ label: 'TENANT_ADMIN' })
  await dialog.getByRole('button', { name: 'Send invitation' }).click()
  await expect(page.getByText(`Invitation sent to ${agentEmail}.`)).toBeVisible()
  const token = await tokenFromEmail(agentEmail, '/invite/accept')
  const agentContext = await browser.newContext()
  const agent = await agentContext.newPage()
  await agent.goto(`/invite/accept?token=${encodeURIComponent(token)}`)
  await agent.getByLabel('First name').fill('Ravi')
  await agent.getByLabel('Last name').fill('Kumar')
  await agent.getByLabel('Password', { exact: true }).fill(PASSWORD)
  await agent.getByLabel('Repeat password', { exact: true }).fill(PASSWORD)
  await agent.getByRole('button', { name: `Join ${ws.name}` }).click()
  await expect(agent.getByRole('heading', { name: "You're in" })).toBeVisible()
  await agentContext.close()

  // Product issues go to Ravi.
  await page
    .getByRole('navigation', { name: 'Settings' })
    .getByRole('link', { name: 'HelpDesk' })
    .click()
  const categories = page.getByRole('region', { name: 'Ticket categories' })
  await categories.getByRole('button', { name: 'Edit Product issue' }).click()
  dialog = page.getByRole('dialog')
  await dialog.getByLabel('Default assignee', { exact: true }).selectOption({ label: 'Ravi Kumar' })
  await dialog.getByRole('button', { name: 'Save changes' }).click()
  await expect(categories.getByRole('row', { name: /Product issue/ })).toContainText('Ravi Kumar')

  // The knowledge base already has the answer.
  await nav.getByRole('link', { name: 'HelpDesk' }).click()
  await helpdeskTab(page, 'Knowledge base').click()
  await page.getByRole('button', { name: 'New article' }).click()
  dialog = page.getByRole('dialog')
  await dialog.getByLabel('Title').fill('Clearing a paper jam')
  await dialog
    .getByLabel('Article')
    .fill('Open the rear tray and pull the jammed sheet out gently.')
  await dialog.getByRole('button', { name: 'Save draft' }).click()
  await expect(page.getByRole('heading', { name: 'Clearing a paper jam' })).toBeVisible()
  await page.getByRole('button', { name: 'Publish' }).click()
  await expect(page.getByText('Article published.')).toBeVisible()

  // The customer, a printer they bought, and their order.
  await nav.getByRole('link', { name: 'Products' }).click()
  await page.getByRole('button', { name: 'New product' }).click()
  dialog = page.getByRole('dialog')
  await dialog.getByLabel('SKU').fill('PR-1')
  await dialog.getByLabel('Name').fill('Office printer')
  await dialog.getByLabel('List price').fill('15000')
  await dialog.getByLabel('Currency').fill('INR')
  await dialog.getByRole('button', { name: 'Create product' }).click()
  await expect(page.getByRole('heading', { name: 'Office printer' })).toBeVisible()
  await nav.getByRole('link', { name: 'Directory' }).click()
  await page.getByRole('button', { name: 'New person' }).click()
  dialog = page.getByRole('dialog')
  await dialog.getByLabel('First name').fill('Meera')
  await dialog.getByLabel('Last name').fill('Iyer')
  await dialog.getByLabel('Email').fill(customerEmail)
  await dialog.getByRole('button', { name: 'Create person' }).click()
  await expect(page.getByRole('heading', { name: 'Meera Iyer' })).toBeVisible()
  const meeraUrl = page.url()
  await nav.getByRole('link', { name: 'Inventory' }).click()
  await page
    .getByRole('navigation', { name: 'Inventory' })
    .getByRole('link', { name: 'Sales orders' })
    .click()
  await page.getByRole('button', { name: 'New sales order' }).click()
  dialog = page.getByRole('dialog')
  await dialog.getByLabel('Customer', { exact: true }).selectOption({ label: 'Meera Iyer' })
  await dialog.getByLabel('Warehouse').selectOption({ label: 'MAIN · Main warehouse' })
  await dialog
    .getByLabel('Line 1 product', { exact: true })
    .selectOption({ label: 'PR-1 · Office printer' })
  await dialog.getByLabel('Currency').fill('INR') // the printer's list price is in INR
  await dialog.getByLabel('Line 1 quantity').fill('1')
  await dialog.getByRole('button', { name: 'Create sales order' }).click()
  await expect(page.getByRole('heading', { name: 'SO-00001' })).toBeVisible()

  // Meera calls: a ticket from her page, about the printer and the order. The category routes it to Ravi.
  await page.goto(meeraUrl)
  await page
    .getByRole('region', { name: 'Tickets' })
    .getByRole('button', { name: 'New ticket' })
    .click()
  dialog = page.getByRole('dialog')
  await expect(dialog.getByLabel('Requester', { exact: true })).toHaveValue(/.+/)
  await dialog.getByLabel('Subject').fill('Printer jams on every page')
  await dialog
    .getByLabel('Description')
    .fill('Every page causes a paper jam since the printer was delivered.')
  await dialog
    .getByLabel('Product', { exact: true })
    .selectOption({ label: 'PR-1 · Office printer' })
  await dialog.getByLabel('Find a related record').fill('SO-00001')
  const order = dialog.getByLabel('Related record', { exact: true }).locator('option', {
    hasText: 'SO-00001',
  })
  await expect(order).toHaveCount(1)
  await dialog
    .getByLabel('Related record', { exact: true })
    .selectOption((await order.getAttribute('value')) ?? '')
  await dialog.getByLabel('Category').selectOption({ label: 'Product issue' })
  await dialog.getByLabel('Priority').selectOption({ label: 'High' })
  await dialog.getByRole('button', { name: 'Create ticket' }).click()
  await expect(
    page.getByRole('heading', { name: 'T-00001 · Printer jams on every page' }),
  ).toBeVisible()
  const details = page.getByRole('region', { name: 'Details' })
  await expect(details).toContainText('Ravi Kumar')
  await expect(details.getByRole('link', { name: /SO-00001/ })).toBeVisible()
  expect(await emailText(agentEmail, "You've been assigned T-00001")).toContain(
    'Printer jams on every page',
  )

  // The ticket page suggests the article; the agent replies with it.
  const context = page.getByRole('region', { name: 'Context' })
  await context.getByRole('button', { name: 'Insert Clearing a paper jam into reply' }).click()
  await expect(page.getByLabel('Message', { exact: true })).toHaveValue(/Open the rear tray/)
  await page.getByRole('button', { name: 'Send reply' }).click()
  await expect(page.getByText(`Reply emailed to ${customerEmail}.`)).toBeVisible()
  const sla = page.getByRole('region', { name: 'SLA' })
  await expect(sla.getByText('First response: Met')).toBeVisible()
  expect(await emailText(customerEmail, '[T-00001] Printer jams on every page')).toContain(
    'Open the rear tray',
  )

  // Waiting on Meera pauses the clock; her answer opens the ticket again.
  await page.getByRole('button', { name: 'Wait on customer' }).click()
  await expect(sla.getByText('Resolution: Paused')).toBeVisible()
  await page.getByLabel('Message type').selectOption('CUSTOMER_MESSAGE')
  await page
    .getByLabel('Message', { exact: true })
    .fill('Meera called back: the tray was the problem.')
  await page.getByRole('button', { name: 'Log customer message' }).click()
  await expect(page.getByText('Customer message logged.')).toBeVisible()
  await expect(sla.getByText('Resolution: On track')).toBeVisible()

  // Resolved with a note.
  await page.getByRole('button', { name: 'Resolve…' }).click()
  dialog = page.getByRole('dialog')
  await dialog.getByLabel('Resolution note').fill('Cleared the rear tray; printing normally.')
  await dialog.getByRole('button', { name: 'Resolve' }).click()
  await expect(page.getByText('T-00001 resolved.')).toBeVisible()
  await expect(sla.getByText('Resolution: Met')).toBeVisible()

  // The dashboard counts one resolved ticket, within SLA.
  await helpdeskTab(page, 'Dashboard').click()
  const month = page.getByRole('region', { name: 'Last 30 days' })
  await expect(month.getByRole('definition').nth(0)).toHaveText('1') // created
  await expect(month.getByRole('definition').nth(1)).toHaveText('1') // resolved
  await expect(month.getByText('First response within SLA').locator('..')).toContainText('100%')
  await expect(month.getByText('Resolved within SLA').locator('..')).toContainText('100%')
})
