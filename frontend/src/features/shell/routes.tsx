import type { RouteObject } from 'react-router'
import { EmptyState } from '@/components/states'
import { CrmLayout } from '@/features/crm/CrmLayout'
import { crmChildren } from '@/features/crm/routes'
import { AuditPage } from '@/features/audit/AuditPage'
import { PERMISSIONS, RequirePermission } from '@/features/auth/permissions'
import { DirectoryPage } from '@/features/directory/DirectoryPage'
import { PartyDetailPage } from '@/features/directory/PartyDetailPage'
import { InventoryLayout } from '@/features/inventory/InventoryLayout'
import { inventoryChildren } from '@/features/inventory/routes'
import { ProductDetailPage } from '@/features/products/ProductDetailPage'
import { ProductsPage } from '@/features/products/ProductsPage'
import { TasksPage } from '@/features/tasks/TasksPage'
import { ModulesSettingsPage } from '@/features/settings/ModulesSettingsPage'
import { PipelineSettingsPage } from '@/features/settings/PipelineSettingsPage'
import { RoleDetailPage } from '@/features/settings/roles/RoleDetailPage'
import { RolesPage } from '@/features/settings/roles/RolesPage'
import { SettingsLayout } from '@/features/settings/SettingsLayout'
import { UsersPage } from '@/features/settings/users/UsersPage'
import { WarehousesSettingsPage } from '@/features/settings/WarehousesSettingsPage'
import { WorkspaceSettingsPage } from '@/features/settings/WorkspaceSettingsPage'
import { AppLayout } from './AppLayout'
import { ComingSoonPage } from './ComingSoonPage'
import { MODULE_PHASES } from './nav'
import { OverviewPage } from './OverviewPage'

function modulePage(path: string, code: string): RouteObject {
  const info = MODULE_PHASES[code]
  return {
    path,
    element: (
      <ComingSoonPage
        title={info.label}
        phase={info.phase}
        description={info.description}
        module={code}
      />
    ),
  }
}

/** /app/settings/* children (Tasks 5–8 add theirs before the catch-all, which must stay last). */
export const settingsChildren: RouteObject[] = [
  {
    path: 'workspace',
    element: (
      <RequirePermission anyOf={[PERMISSIONS.settingsRead]}>
        <WorkspaceSettingsPage />
      </RequirePermission>
    ),
  },
  {
    path: 'modules',
    element: (
      <RequirePermission anyOf={[PERMISSIONS.settingsRead]}>
        <ModulesSettingsPage />
      </RequirePermission>
    ),
  },
  {
    path: 'users',
    element: (
      <RequirePermission anyOf={[PERMISSIONS.userRead]}>
        <UsersPage />
      </RequirePermission>
    ),
  },
  {
    path: 'roles',
    element: (
      <RequirePermission anyOf={[PERMISSIONS.roleRead]}>
        <RolesPage />
      </RequirePermission>
    ),
  },
  {
    path: 'roles/:roleId',
    element: (
      <RequirePermission anyOf={[PERMISSIONS.roleRead]}>
        <RoleDetailPage />
      </RequirePermission>
    ),
  },
  {
    path: 'pipeline',
    element: (
      <RequirePermission anyOf={[PERMISSIONS.pipelineManage]}>
        <PipelineSettingsPage />
      </RequirePermission>
    ),
  },
  {
    path: 'warehouses',
    element: (
      <RequirePermission anyOf={[PERMISSIONS.warehouseManage]}>
        <WarehousesSettingsPage />
      </RequirePermission>
    ),
  },
  {
    path: '*',
    element: <EmptyState title="Settings page not found" description="Pick a section above." />,
  },
]

/** /app/* children. */
export const appChildren: RouteObject[] = [
  { index: true, element: <OverviewPage /> },
  {
    path: 'directory',
    element: (
      <RequirePermission anyOf={[PERMISSIONS.partyRead]}>
        <DirectoryPage />
      </RequirePermission>
    ),
  },
  {
    path: 'directory/:partyId',
    element: (
      <RequirePermission anyOf={[PERMISSIONS.partyRead]}>
        <PartyDetailPage />
      </RequirePermission>
    ),
  },
  {
    path: 'products',
    element: (
      <RequirePermission anyOf={[PERMISSIONS.productRead]}>
        <ProductsPage />
      </RequirePermission>
    ),
  },
  {
    path: 'products/:productId',
    element: (
      <RequirePermission anyOf={[PERMISSIONS.productRead]}>
        <ProductDetailPage />
      </RequirePermission>
    ),
  },
  {
    path: 'tasks',
    element: (
      <RequirePermission anyOf={[PERMISSIONS.taskRead]}>
        <TasksPage />
      </RequirePermission>
    ),
  },
  { path: 'crm', element: <CrmLayout />, children: crmChildren },
  { path: 'inventory', element: <InventoryLayout />, children: inventoryChildren },
  modulePage('helpdesk', 'HELPDESK'),
  modulePage('hrms', 'HRMS'),
  {
    path: 'workflows',
    element: (
      <ComingSoonPage
        title="Workflows"
        phase={9}
        description="Visual, auditable business workflows."
      />
    ),
  },
  {
    path: 'insights',
    element: (
      <ComingSoonPage
        title="Insights"
        phase={11}
        description="Operational dashboards across modules."
      />
    ),
  },
  {
    path: 'assistant',
    element: (
      <ComingSoonPage
        title="AI Assistant"
        phase={12}
        description="Permission-aware answers about your workspace."
      />
    ),
  },
  {
    path: 'audit',
    element: (
      <RequirePermission anyOf={[PERMISSIONS.auditRead]}>
        <AuditPage />
      </RequirePermission>
    ),
  },
  { path: 'settings', element: <SettingsLayout />, children: settingsChildren },
]

export const appRoutes: RouteObject[] = [
  { path: '/app', element: <AppLayout />, children: appChildren },
]
