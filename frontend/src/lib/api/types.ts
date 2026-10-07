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

export type PartyKind = 'PERSON' | 'ORGANIZATION'
export type PartyRoleType = 'CUSTOMER' | 'SUPPLIER' | 'EMPLOYEE'
export type RoleStatus = 'ACTIVE' | 'INACTIVE'

export interface PartyRef {
  id: string
  name: string
}

export interface PartyRoleView {
  role: PartyRoleType
  status: RoleStatus
  since: string | null
  employeeNumber: string | null
}

export interface PartyView {
  id: string
  kind: PartyKind
  name: string
  firstName: string | null
  lastName: string | null
  jobTitle: string | null
  organization: PartyRef | null
  email: string | null
  phone: string | null
  domain: string | null
  website: string | null
  roles: PartyRoleView[]
  duplicateReason: string | null
  archivedAt: string | null
  createdAt: string
  updatedAt: string
  version: number
}

export interface PartySummary {
  id: string
  kind: PartyKind
  name: string
  email: string | null
  phone: string | null
  domain: string | null
  organization: PartyRef | null
  roles: PartyRoleType[]
  archived: boolean
}

export interface DuplicateCandidate {
  id: string
  kind: PartyKind
  name: string
  email: string | null
  domain: string | null
  archived: boolean
}

export type ProductKind = 'GOODS' | 'SERVICE'

export interface ProductView {
  id: string
  sku: string
  name: string
  description: string | null
  kind: ProductKind
  unit: string
  listPrice: number | null
  currency: string | null
  archivedAt: string | null
  createdAt: string
  updatedAt: string
  version: number
}

/** Record types activities, tasks and documents attach to (server SubjectResolver codes). */
export type SubjectType = 'PARTY' | 'PRODUCT'

export interface MemberRef {
  id: string
  name: string
}

export interface SubjectRef {
  type: string
  id: string
  /** null when the viewer can't read that record */
  label: string | null
  archived: boolean
}

export type ActivityType = 'NOTE' | 'CALL' | 'EMAIL' | 'MEETING'

export interface ActivityView {
  id: string
  subjectType: string
  subjectId: string
  type: ActivityType
  summary: string
  body: string | null
  occurredAt: string
  author: MemberRef | null
  createdAt: string
}

export type TaskStatus = 'OPEN' | 'IN_PROGRESS' | 'DONE' | 'CANCELLED'
export type TaskPriority = 'LOW' | 'NORMAL' | 'HIGH' | 'URGENT'

export interface TaskView {
  id: string
  title: string
  description: string | null
  status: TaskStatus
  priority: TaskPriority
  dueOn: string | null
  assignee: MemberRef | null
  subject: SubjectRef | null
  createdBy: MemberRef | null
  completedAt: string | null
  createdAt: string
  updatedAt: string
  version: number
}

export interface AssigneeView {
  id: string
  name: string
  email: string
}

export interface DocumentView {
  id: string
  subjectType: string
  subjectId: string
  fileName: string
  contentType: string
  sizeBytes: number
  sha256: string
  uploadedBy: MemberRef | null
  createdAt: string
}
