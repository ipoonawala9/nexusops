import type { RouteObject } from 'react-router'
import { PERMISSIONS, RequirePermission } from '@/features/auth/permissions'
import { Customer360Page } from './Customer360Page'
import { CrmDashboardPage } from './CrmDashboardPage'
import { CustomersPage } from './CustomersPage'
import { LeadDetailPage } from './LeadDetailPage'
import { LeadsPage } from './LeadsPage'
import { OpportunityDetailPage } from './OpportunityDetailPage'
import { PipelinePage } from './PipelinePage'

export const crmChildren: RouteObject[] = [
  {
    index: true,
    element: (
      <RequirePermission anyOf={[PERMISSIONS.leadRead, PERMISSIONS.opportunityRead]}>
        <CrmDashboardPage />
      </RequirePermission>
    ),
  },
  {
    path: 'leads',
    element: (
      <RequirePermission anyOf={[PERMISSIONS.leadRead]}>
        <LeadsPage />
      </RequirePermission>
    ),
  },
  {
    path: 'leads/:leadId',
    element: (
      <RequirePermission anyOf={[PERMISSIONS.leadRead]}>
        <LeadDetailPage />
      </RequirePermission>
    ),
  },
  {
    path: 'pipeline',
    element: (
      <RequirePermission anyOf={[PERMISSIONS.opportunityRead]}>
        <PipelinePage />
      </RequirePermission>
    ),
  },
  {
    path: 'opportunities/:opportunityId',
    element: (
      <RequirePermission anyOf={[PERMISSIONS.opportunityRead]}>
        <OpportunityDetailPage />
      </RequirePermission>
    ),
  },
  {
    path: 'customers',
    element: (
      <RequirePermission anyOf={[PERMISSIONS.customerRead]}>
        <CustomersPage />
      </RequirePermission>
    ),
  },
  {
    path: 'customers/:partyId',
    element: (
      <RequirePermission anyOf={[PERMISSIONS.customerRead]}>
        <Customer360Page />
      </RequirePermission>
    ),
  },
]
