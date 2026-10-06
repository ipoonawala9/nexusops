/** Mirrors docs/api/openapi.json. Field names and value spellings are the server's. */
export interface Page<T> {
  items: T[]
  page: number
  size: number
  total: number
}

export type UserStatus = 'INVITED' | 'ACTIVE' | 'DISABLED'
export type TenantStatus = 'PENDING_VERIFICATION' | 'ACTIVE' | 'SUSPENDED'
export type InvitationStatus = 'PENDING' | 'ACCEPTED' | 'REVOKED' | 'EXPIRED'

export interface RoleRef {
  id: string
  name: string
}

export interface UserView {
  id: string
  email: string
  firstName: string
  lastName: string
  status: UserStatus
  emailVerified: boolean
  roles: RoleRef[]
  lastLoginAt: string | null
  createdAt: string
}

export interface TenantSummary {
  id: string
  slug: string
  name: string
  status: TenantStatus
  planCode: string
}

export interface Profile {
  user: UserView
  tenant: TenantSummary
  permissions: string[]
  modules: string[]
}

export interface TenantSettings extends TenantSummary {
  timezone: string
  locale: string
  currency: string
}

export interface ModuleState {
  code: string
  name: string
  enabled: boolean
}

export interface PermissionView {
  code: string
  module: string | null
  description: string
  moduleEnabled: boolean
}

export interface RoleView {
  id: string
  name: string
  description: string | null
  system: boolean
  permissions: string[]
}

export interface InvitationView {
  id: string
  email: string
  roleId: string
  roleName: string
  status: InvitationStatus
  invitedBy: string | null
  expiresAt: string
  createdAt: string
}

export interface InvitationPreview {
  workspace: string
  workspaceName: string
  email: string
  roleName: string
  expiresAt: string
}

export interface AcceptedInvitation {
  workspace: string
  email: string
}

export interface AuditEvent {
  id: string
  occurredAt: string
  actorType: string
  actorId: string | null
  action: string
  entityType: string | null
  entityId: string | null
  ip: string | null
  userAgent: string | null
  requestId: string | null
  correlationId: string | null
  before: unknown
  after: unknown
  metadata: unknown
}

export interface TokenResponse {
  accessToken: string
  tokenType: string
  expiresIn: number
}

export type PlatformRole = 'PLATFORM_ADMIN' | 'PLATFORM_SUPPORT'

export interface PlatformMe {
  id: string
  email: string
  role: PlatformRole
  permissions: string[]
}

export interface PlatformTenant {
  id: string
  slug: string
  name: string
  status: TenantStatus
  planCode: string
  createdAt: string
  activeUsers: number
  ownerEmails: string[]
}
