import { screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import { aParty, aPurchaseSummary, aSalesSummary, pageOf } from '@/test/records'
import { renderApp } from '@/test/renderApp'

function setup(modules: string[], purchases = [aPurchaseSummary({ status: 'ORDERED' })]) {
  const server = fakeServer()
  signedIn(server, testProfile({ modules }))
    .on('GET /parties/:id', { body: aParty() })
    .on('GET /parties', { body: pageOf([]) })
    .on('GET /activities', { body: pageOf([]) })
    .on('GET /documents', { body: [] })
    .on('GET /purchase-orders', { body: pageOf(purchases) })
    .on('GET /sales-orders', { body: pageOf([aSalesSummary({ status: 'FULFILLED' })]) })
  return renderApp({ server, path: '/app/directory/p-acme' })
}

describe('PartyOrdersPanel', () => {
  it("lists the party's purchase and sales orders", async () => {
    const { server } = setup(['INVENTORY'])
    const panel = await screen.findByRole('region', { name: 'Orders' })
    expect(await within(panel).findByRole('link', { name: 'PO-00001' })).toBeInTheDocument()
    expect(within(panel).getByText('Ordered')).toBeInTheDocument()
    expect(within(panel).getByRole('link', { name: 'SO-00001' })).toBeInTheDocument()
    expect(within(panel).getByText('Fulfilled')).toBeInTheDocument()
    expect(server.callsTo('GET /purchase-orders')[0].query.get('supplierId')).toBe('p-acme')
    expect(server.callsTo('GET /sales-orders')[0].query.get('customerId')).toBe('p-acme')
  })

  it('is absent without Inventory', async () => {
    setup([])
    await screen.findByRole('heading', { name: 'Acme' })
    expect(screen.queryByRole('region', { name: 'Orders' })).not.toBeInTheDocument()
  })
})
