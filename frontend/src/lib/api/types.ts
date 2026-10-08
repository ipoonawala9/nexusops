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
export type SubjectType = 'PARTY' | 'PRODUCT' | 'LEAD' | 'OPPORTUNITY'

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
  /** The record the activity is on; its label is null when the viewer can't read it. */
  subject: SubjectRef | null
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

export type StageKind = 'OPEN' | 'WON' | 'LOST'

export interface StageView {
  id: string
  name: string
  probability: number
  kind: StageKind
  position: number
  version: number
}

export interface StageRef {
  id: string
  name: string
  kind: StageKind
  probability: number
}

/** A sum in one currency: amounts in different currencies are never added. */
export interface MoneyTotal {
  currency: string
  amount: number
}

export type LeadSource =
  'WEBSITE' | 'REFERRAL' | 'WALK_IN' | 'PHONE' | 'EMAIL' | 'SOCIAL' | 'EVENT' | 'OTHER'
export type LeadStatus = 'NEW' | 'CONTACTED' | 'QUALIFIED' | 'DISQUALIFIED' | 'CONVERTED'

export interface LeadView {
  id: string
  name: string
  firstName: string | null
  lastName: string | null
  companyName: string | null
  jobTitle: string | null
  email: string | null
  phone: string | null
  source: LeadSource
  status: LeadStatus
  owner: MemberRef | null
  estimatedValue: number | null
  currency: string | null
  description: string | null
  disqualifyReason: string | null
  disqualifiedAt: string | null
  convertedAt: string | null
  convertedPerson: PartyRef | null
  convertedOrganization: PartyRef | null
  convertedOpportunityId: string | null
  createdBy: MemberRef | null
  createdAt: string
  updatedAt: string
  version: number
}

export type OpportunityStatus = StageKind

export interface OpportunityView {
  id: string
  name: string
  account: PartyRef | null
  contact: PartyRef | null
  stage: StageRef
  status: OpportunityStatus
  amount: number | null
  currency: string | null
  expectedCloseOn: string | null
  owner: MemberRef | null
  leadId: string | null
  description: string | null
  lostReason: string | null
  closedAt: string | null
  createdBy: MemberRef | null
  createdAt: string
  updatedAt: string
  version: number
}

export interface OpportunitySummary {
  id: string
  name: string
  account: PartyRef | null
  stage: StageRef
  amount: number | null
  currency: string | null
  expectedCloseOn: string | null
  owner: MemberRef | null
  version: number
}

export interface BoardColumn {
  stage: StageView
  count: number
  totals: MoneyTotal[]
  weighted: MoneyTotal[]
  opportunities: OpportunitySummary[]
}

export interface BoardView {
  columns: BoardColumn[]
}

export interface CustomerRow {
  party: PartySummary
  openCount: number
  openValue: MoneyTotal[]
  wonCount: number
  wonValue: MoneyTotal[]
}

export interface CustomerSummary {
  party: PartyView
  openCount: number
  openValue: MoneyTotal[]
  weightedValue: MoneyTotal[]
  wonCount: number
  wonValue: MoneyTotal[]
  lostCount: number
  leadCount: number
}

export interface LeadStats {
  open: Partial<Record<LeadStatus, number>>
  newLast30Days: number
  converted90Days: number
  disqualified90Days: number
  /** 0–1, null when no lead closed in the last 90 days */
  conversionRate: number | null
}

export interface StageStats {
  stage: StageView
  count: number
  totals: MoneyTotal[]
  weighted: MoneyTotal[]
}

export interface ClosedStats {
  count: number
  totals: MoneyTotal[]
}

export interface PipelineStats {
  stages: StageStats[]
  wonThisMonth: ClosedStats
  lostThisMonth: ClosedStats
  closingSoon: OpportunitySummary[]
}

/** A section is null when the viewer can't read it. */
export interface DashboardView {
  leads: LeadStats | null
  pipeline: PipelineStats | null
}

export interface SearchHit {
  type: string
  id: string
  label: string
  detail: string | null
  archived: boolean
}

export interface ImportResult {
  imported: number
}
