import { expect, test, type Page } from '@playwright/test'
import { newWorkspace, signIn, signUpAndVerify } from './support/workspace'

async function newOrganization(page: Page, name: string) {
  await page
    .getByRole('navigation', { name: 'Workspace' })
    .getByRole('link', { name: 'Directory' })
    .click()
  await page.getByRole('button', { name: 'New organization' }).click()
  const dialog = page.getByRole('dialog')
  await dialog.getByLabel('Name').fill(name)
  await dialog.getByRole('button', { name: 'Create organization' }).click()
  await expect(page.getByRole('heading', { name })).toBeVisible()
}

function inventoryTab(page: Page, name: string) {
  return page.getByRole('navigation', { name: 'Inventory' }).getByRole('link', { name })
}

test('stock is counted, sold, explained, reordered and received in two parts', async ({ page }) => {
  const ws = newWorkspace('inv')
  await signUpAndVerify(page, ws)
  await signIn(page, ws, ws.email)
  await expect(page.getByRole('heading', { name: 'Welcome, Ada' })).toBeVisible()
  const nav = page.getByRole('navigation', { name: 'Workspace' })

  // Enable Inventory; a supplier and a customer exist in the directory.
  await page.goto('/app/settings/modules')
  await page.getByRole('switch', { name: 'Inventory' }).click()
  await expect(page.getByText('Inventory enabled.')).toBeVisible()
  await newOrganization(page, 'Konkan Supplies')
  await newOrganization(page, 'Deccan Retail')

  // A stocked product with a list price, counted in at 50.
  await nav.getByRole('link', { name: 'Products' }).click()
  await page.getByRole('button', { name: 'New product' }).click()
  let dialog = page.getByRole('dialog')
  await dialog.getByLabel('SKU').fill('W-1')
  await dialog.getByLabel('Name').fill('Widget')
  await dialog.getByLabel('List price').fill('12.50')
  await dialog.getByLabel('Currency').fill('USD')
  await dialog.getByRole('button', { name: 'Create product' }).click()
  await expect(page.getByRole('heading', { name: 'Widget' })).toBeVisible()
  const stock = page.getByRole('region', { name: 'Stock' })
  await stock.getByRole('button', { name: 'Count' }).click()
  dialog = page.getByRole('dialog')
  await dialog.getByLabel('Warehouse').selectOption({ label: 'MAIN · Main warehouse' })
  await dialog.getByLabel('Counted quantity').fill('50')
  await dialog.getByLabel('Reason').fill('Opening count')
  await dialog.getByRole('button', { name: 'Save count' }).click()
  await expect(page.getByText('Count saved.')).toBeVisible()
  await expect(stock.getByRole('row', { name: /MAIN/ }).first()).toContainText('50')

  // A reorder rule with a preferred supplier.
  await nav.getByRole('link', { name: 'Inventory' }).click()
  await inventoryTab(page, 'Reorder').click()
  await page.getByRole('button', { name: 'New rule' }).click()
  dialog = page.getByRole('dialog')
  await dialog.getByLabel('Product', { exact: true }).selectOption({ label: 'W-1 · Widget' })
  await dialog.getByLabel('Warehouse').selectOption({ label: 'MAIN · Main warehouse' })
  await dialog.getByLabel('Minimum').fill('20')
  await dialog.getByLabel('Maximum').fill('60')
  await dialog
    .getByLabel('Preferred supplier', { exact: true })
    .selectOption({ label: 'Konkan Supplies' })
  await dialog.getByRole('button', { name: 'Save rule' }).click()
  await expect(page.getByText('Reorder rule saved.')).toBeVisible()
  await expect(page.getByText('Nothing to reorder.')).toBeVisible()

  // Sell 38 and ship them: below the minimum now.
  await inventoryTab(page, 'Sales orders').click()
  await page.getByRole('button', { name: 'New sales order' }).click()
  dialog = page.getByRole('dialog')
  await dialog.getByLabel('Customer', { exact: true }).selectOption({ label: 'Deccan Retail' })
  await dialog.getByLabel('Warehouse').selectOption({ label: 'MAIN · Main warehouse' })
  await dialog.getByLabel('Line 1 product', { exact: true }).selectOption({ label: 'W-1 · Widget' })
  await dialog.getByLabel('Line 1 quantity').fill('38')
  await dialog.getByRole('button', { name: 'Create sales order' }).click()
  await expect(page.getByRole('heading', { name: 'SO-00001' })).toBeVisible()
  await page.getByRole('button', { name: 'Confirm' }).click()
  await expect(page.getByText('SO-00001 confirmed.')).toBeVisible()
  await page.getByRole('button', { name: 'Fulfil' }).click()
  await expect(page.getByText('SO-00001 fulfilled.')).toBeVisible()

  // The suggestion explains itself and becomes a draft purchase order.
  await inventoryTab(page, 'Reorder').click()
  await expect(
    page.getByText(
      '12 available, 0 on order, below the minimum of 20; 38 used in the last 30 days (about 9 days of cover); order 48 to reach 60',
    ),
  ).toBeVisible()
  await page.getByLabel('Select W-1 at MAIN').check()
  await page.getByRole('button', { name: 'Create purchase orders' }).click()
  await page.getByRole('link', { name: 'PO-00001' }).click()

  // Place it and receive it in two parts.
  await expect(page.getByRole('heading', { name: 'PO-00001' })).toBeVisible()
  await page.getByRole('button', { name: 'Place order' }).click()
  await expect(page.getByText('PO-00001 placed.')).toBeVisible()
  await page.getByRole('button', { name: 'Receive' }).click()
  dialog = page.getByRole('dialog')
  await dialog.getByLabel('Receive W-1 (48 due)').fill('20')
  await dialog.getByRole('button', { name: 'Record receipt' }).click()
  await expect(page.getByText('Partly received')).toBeVisible()
  await page.getByRole('button', { name: 'Receive' }).click()
  dialog = page.getByRole('dialog')
  await expect(dialog.getByLabel('Receive W-1 (28 due)')).toHaveValue('28')
  await dialog.getByRole('button', { name: 'Record receipt' }).click()
  await expect(page.getByText('Received', { exact: true })).toBeVisible()

  // Stock and the ledger show every step: 50 − 38 + 20 + 28 = 60.
  await page.getByRole('link', { name: 'W-1' }).first().click()
  await expect(page.getByRole('heading', { name: 'Widget' })).toBeVisible()
  await expect(stock.getByRole('row', { name: /MAIN/ }).first()).toContainText('60')
  for (const reference of ['PO-00001', 'SO-00001', 'Opening count']) {
    await expect(stock.getByText(reference).first()).toBeVisible()
  }
  await expect(stock.getByText('Receipt')).toHaveCount(2)
  await expect(stock.getByText('Issue')).toHaveCount(1)
})
