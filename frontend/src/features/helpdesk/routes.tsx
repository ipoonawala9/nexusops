import type { RouteObject } from 'react-router'
import { PERMISSIONS, RequirePermission } from '@/features/auth/permissions'
import { HelpDeskIndex } from './HelpDeskIndex'
import { TicketsPage } from './TicketsPage'

/** /app/helpdesk/* children (Tasks 9–10 add theirs). */
export const helpdeskChildren: RouteObject[] = [
  { index: true, element: <HelpDeskIndex /> },
  {
    path: 'tickets',
    element: (
      <RequirePermission anyOf={[PERMISSIONS.ticketRead]}>
        <TicketsPage />
      </RequirePermission>
    ),
  },
]
