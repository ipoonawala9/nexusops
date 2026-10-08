import type { ReactNode } from 'react'
import { NoAccess } from '@/components/states'
import { useTenantSession } from './tenantSession'

/** Tenant permission codes (V3, V9–V15 catalog). The UI hides what these deny; the server enforces them. */
export const PERMISSIONS = {
  settingsRead: 'tenant.settings.read',
  settingsUpdate: 'tenant.settings.update',
  modulesManage: 'tenant.modules.manage',
  userRead: 'identity.user.read',
  userInvite: 'identity.user.invite',
  userUpdate: 'identity.user.update',
  userDisable: 'identity.user.disable',
  roleRead: 'authorization.role.read',
  roleManage: 'authorization.role.manage',
  roleAssign: 'authorization.role.assign',
  auditRead: 'audit.event.read',
  partyRead: 'directory.party.read',
  partyManage: 'directory.party.manage',
  employeeRead: 'directory.employee.read',
  employeeManage: 'directory.employee.manage',
  productRead: 'catalog.product.read',
  productManage: 'catalog.product.manage',
  activityCreate: 'collaboration.activity.create',
  taskRead: 'collaboration.task.read',
  taskManage: 'collaboration.task.manage',
  documentRead: 'collaboration.document.read',
  documentManage: 'collaboration.document.manage',
  leadRead: 'crm.lead.read',
  leadManage: 'crm.lead.manage',
  opportunityRead: 'crm.opportunity.read',
  opportunityManage: 'crm.opportunity.manage',
  pipelineManage: 'crm.pipeline.manage',
  customerRead: 'crm.customer.read',
} as const

export type PermissionCode = (typeof PERMISSIONS)[keyof typeof PERMISSIONS]

export const ALL_TENANT_PERMISSIONS: readonly PermissionCode[] = Object.values(PERMISSIONS)

/** Returns can(...codes): true when the signed-in user holds ANY of the codes. */
export function useCan(): (...codes: PermissionCode[]) => boolean {
  const { state } = useTenantSession()
  const held = state.status === 'authenticated' ? state.profile.permissions : []
  return (...codes) => codes.some((code) => held.includes(code))
}

export function RequirePermission({
  anyOf,
  children,
}: {
  anyOf: PermissionCode[]
  children: ReactNode
}) {
  const can = useCan()
  return can(...anyOf) ? <>{children}</> : <NoAccess />
}
