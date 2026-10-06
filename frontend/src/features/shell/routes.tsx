import type { RouteObject } from 'react-router'
import { EmptyState } from '@/components/states'
import { PERMISSIONS, RequirePermission } from '@/features/auth/permissions'
import { ModulesSettingsPage } from '@/features/settings/ModulesSettingsPage'
import { SettingsLayout } from '@/features/settings/SettingsLayout'
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
    path: '*',
    element: <EmptyState title="Settings page not found" description="Pick a section above." />,
  },
]

/** /app/* children (Task 9 appends the audit route). */
export const appChildren: RouteObject[] = [
  { index: true, element: <OverviewPage /> },
  modulePage('crm', 'CRM'),
  modulePage('inventory', 'INVENTORY'),
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
  { path: 'settings', element: <SettingsLayout />, children: settingsChildren },
]

export const appRoutes: RouteObject[] = [
  { path: '/app', element: <AppLayout />, children: appChildren },
]
