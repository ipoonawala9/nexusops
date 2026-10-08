import type { RouteObject } from 'react-router'
import { EmptyState } from '@/components/states'
import { PERMISSIONS, RequirePermission } from '@/features/auth/permissions'
import { LeadDetailPage } from './LeadDetailPage'
import { LeadsPage } from './LeadsPage'

function Pending({ title }: { title: string }) {
  return <EmptyState title={title} description="This page is being built." />
}

export const crmChildren: RouteObject[] = [
  {
    index: true,
    element: (
      <RequirePermission anyOf={[PERMISSIONS.leadRead, PERMISSIONS.opportunityRead]}>
        <Pending title="Dashboard" />
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
        <Pending title="Pipeline" />
      </RequirePermission>
    ),
  },
  {
    path: 'opportunities/:opportunityId',
    element: (
      <RequirePermission anyOf={[PERMISSIONS.opportunityRead]}>
        <Pending title="Opportunity" />
      </RequirePermission>
    ),
  },
  {
    path: 'customers',
    element: (
      <RequirePermission anyOf={[PERMISSIONS.customerRead]}>
        <Pending title="Customers" />
      </RequirePermission>
    ),
  },
  {
    path: 'customers/:partyId',
    element: (
      <RequirePermission anyOf={[PERMISSIONS.customerRead]}>
        <Pending title="Customer" />
      </RequirePermission>
    ),
  },
]
