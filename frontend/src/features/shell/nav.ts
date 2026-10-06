import { PERMISSIONS, type PermissionCode } from '@/features/auth/permissions'

export interface NavItem {
  to: string
  label: string
  /** Shown only when this module is enabled for the workspace (blueprint §23). */
  module?: string
  /** Shown only with ANY of these permissions. */
  anyOf?: PermissionCode[]
  /** Blueprint phase that delivers it, for "coming soon" entries. */
  phase?: number
}

export const MODULE_PHASES: Record<string, { label: string; phase: number; description: string }> =
  {
    CRM: { label: 'CRM', phase: 5, description: 'Customers, contacts, leads and opportunities.' },
    INVENTORY: {
      label: 'Inventory',
      phase: 6,
      description: 'Products, warehouses and stock movements.',
    },
    HELPDESK: { label: 'HelpDesk', phase: 7, description: 'Tickets, SLAs and customer support.' },
    HRMS: { label: 'HRMS', phase: 8, description: 'Employees, onboarding and leave.' },
  }

export const NAV_ITEMS: NavItem[] = [
  { to: '/app', label: 'Overview' },
  { to: '/app/crm', label: 'CRM', module: 'CRM' },
  { to: '/app/inventory', label: 'Inventory', module: 'INVENTORY' },
  { to: '/app/helpdesk', label: 'HelpDesk', module: 'HELPDESK' },
  { to: '/app/hrms', label: 'HRMS', module: 'HRMS' },
  { to: '/app/workflows', label: 'Workflows', phase: 9 },
  { to: '/app/insights', label: 'Insights', phase: 11 },
  { to: '/app/assistant', label: 'AI Assistant', phase: 12 },
  { to: '/app/audit', label: 'Audit', anyOf: [PERMISSIONS.auditRead] },
]

export const SETTINGS_TABS: Array<{ to: string; label: string; anyOf: PermissionCode[] }> = [
  { to: '/app/settings/workspace', label: 'Workspace', anyOf: [PERMISSIONS.settingsRead] },
  { to: '/app/settings/users', label: 'Users', anyOf: [PERMISSIONS.userRead] },
  { to: '/app/settings/roles', label: 'Roles', anyOf: [PERMISSIONS.roleRead] },
  { to: '/app/settings/modules', label: 'Modules', anyOf: [PERMISSIONS.settingsRead] },
]

export function firstSettingsPath(can: (...codes: PermissionCode[]) => boolean): string | null {
  return SETTINGS_TABS.find((tab) => can(...tab.anyOf))?.to ?? null
}
