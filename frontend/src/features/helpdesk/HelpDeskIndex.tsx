import { Navigate } from 'react-router'
import { PERMISSIONS, useCan } from '@/features/auth/permissions'
import { HelpDeskDashboardPage } from './HelpDeskDashboardPage'
import { TABS } from './HelpDeskLayout'

/** The dashboard for ticket readers; anyone else lands on the first section they may open. */
export function HelpDeskIndex() {
  const can = useCan()
  if (can(PERMISSIONS.ticketRead)) return <HelpDeskDashboardPage />
  const first = TABS.find((tab) => can(...tab.anyOf))
  return first ? <Navigate to={first.to} replace /> : null
}
