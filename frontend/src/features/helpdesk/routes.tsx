import type { RouteObject } from 'react-router'
import { PERMISSIONS, RequirePermission } from '@/features/auth/permissions'
import { ArticleDetailPage } from './ArticleDetailPage'
import { ArticlesPage } from './ArticlesPage'
import { HelpDeskIndex } from './HelpDeskIndex'
import { TicketDetailPage } from './TicketDetailPage'
import { TicketsPage } from './TicketsPage'

/** /app/helpdesk/* children. */
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
  {
    path: 'tickets/:ticketId',
    element: (
      <RequirePermission anyOf={[PERMISSIONS.ticketRead]}>
        <TicketDetailPage />
      </RequirePermission>
    ),
  },
  {
    path: 'articles',
    element: (
      <RequirePermission anyOf={[PERMISSIONS.articleRead]}>
        <ArticlesPage />
      </RequirePermission>
    ),
  },
  {
    path: 'articles/:articleId',
    element: (
      <RequirePermission anyOf={[PERMISSIONS.articleRead]}>
        <ArticleDetailPage />
      </RequirePermission>
    ),
  },
]
