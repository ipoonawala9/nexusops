import { screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import { aHelpDeskDashboard } from '@/test/records'
import { renderApp } from '@/test/renderApp'

function setup(dashboard = aHelpDeskDashboard()) {
  const server = fakeServer()
  signedIn(server, testProfile({ modules: ['HELPDESK'] })).on('GET /helpdesk/dashboard', {
    body: dashboard,
  })
  return renderApp({ server, path: '/app/helpdesk' })
}

describe('HelpDeskDashboardPage', () => {
  it('shows open work with links to the matching tickets', async () => {
    setup()
    const open = await screen.findByRole('region', { name: 'Open tickets' })
    for (const [name, href, count] of [
      ['Breached', '/app/helpdesk/tickets?sla=breached', '1'],
      ['At risk', '/app/helpdesk/tickets?sla=at_risk', '1'],
      ['Unassigned', '/app/helpdesk/tickets?assignee=unassigned', '2'],
    ]) {
      const link = within(open).getByRole('link', { name })
      expect(link).toHaveAttribute('href', href)
      expect(link.closest('div')).toHaveTextContent(`${name}${count}`)
    }
    expect(within(open).getByText('Waiting on customer')).toBeInTheDocument()
    const priority = screen.getByRole('region', { name: 'Open by priority' })
    expect(within(priority).getByText('Urgent')).toBeInTheDocument()
  })

  it('shows the last 30 days in plain units', async () => {
    setup()
    const month = await screen.findByRole('region', { name: 'Last 30 days' })
    expect(within(month).getByText('1 h 35 min')).toBeInTheDocument() // average first response
    expect(within(month).getByText('1 h')).toBeInTheDocument() // median first response
    expect(within(month).getByText('1 d 1 h')).toBeInTheDocument() // average resolution
    expect(within(month).getByText('75%')).toBeInTheDocument()
    expect(within(month).getByText('25%')).toBeInTheDocument()
  })

  it('shows dashes when nothing can be measured yet', async () => {
    setup(
      aHelpDeskDashboard({
        openByStatus: { NEW: 0, OPEN: 0, PENDING: 0 },
        unassigned: 0,
        breached: 0,
        atRisk: 0,
        last30Days: { created: 0, resolved: 0 },
      }),
    )
    const month = await screen.findByRole('region', { name: 'Last 30 days' })
    expect(within(month).getAllByText('—').length).toBeGreaterThanOrEqual(6)
  })
})
