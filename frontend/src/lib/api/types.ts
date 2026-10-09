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
export type SubjectType =
  | 'PARTY'
  | 'PRODUCT'
  | 'LEAD'
  | 'OPPORTUNITY'
  | 'PURCHASE_ORDER'
  | 'SALES_ORDER'
  | 'TICKET'
  | 'KB_ARTICLE'

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

export interface WarehouseRef {
  id: string
  code: string
  name: string
}

export interface WarehouseView extends WarehouseRef {
  address: string | null
  archivedAt: string | null
  version: number
}

export interface StockProductRef {
  id: string
  sku: string
  name: string
  unit: string
}

export interface StockLevelView {
  warehouse: WarehouseRef
  onHand: number
  reserved: number
  available: number
}

export interface ProductStock {
  product: StockProductRef
  levels: StockLevelView[]
  onHand: number
  reserved: number
  available: number
}

export type MovementKind = 'RECEIPT' | 'ISSUE' | 'ADJUSTMENT' | 'TRANSFER_OUT' | 'TRANSFER_IN'
export type ReferenceType = 'PURCHASE_ORDER' | 'SALES_ORDER' | 'TRANSFER' | 'ADJUSTMENT'

export interface MovementView {
  id: string
  product: StockProductRef
  warehouse: WarehouseRef
  kind: MovementKind
  /** Signed: issues and transfers out are negative. */
  quantity: number
  onHandAfter: number
  referenceType: ReferenceType
  referenceId: string
  reason: string | null
  actor: MemberRef | null
  occurredAt: string
}

export interface StockRow {
  product: StockProductRef
  warehouse: WarehouseRef
  onHand: number
  reserved: number
  available: number
  onOrder: number
  ruleId: string | null
  minQuantity: number | null
  maxQuantity: number | null
  belowMin: boolean
}

/** 409 "Not enough stock." carries these in `shortages`. */
export interface Shortage {
  productId: string
  sku: string
  requested: number
  available: number
}

export type PurchaseOrderStatus =
  'DRAFT' | 'ORDERED' | 'PARTIALLY_RECEIVED' | 'RECEIVED' | 'CANCELLED'

export interface PurchaseLineView {
  id: string
  lineNo: number
  product: StockProductRef
  quantity: number
  receivedQuantity: number
  remainingQuantity: number
  unitCost: number
  lineTotal: number
}

export interface PurchaseOrderView {
  id: string
  number: string
  supplier: PartyRef | null
  warehouse: WarehouseRef
  status: PurchaseOrderStatus
  currency: string
  expectedOn: string | null
  notes: string | null
  lines: PurchaseLineView[]
  total: number
  orderedAt: string | null
  receivedAt: string | null
  cancelledAt: string | null
  createdBy: MemberRef | null
  createdAt: string
  updatedAt: string
  version: number
}

export interface PurchaseOrderSummary {
  id: string
  number: string
  supplier: PartyRef | null
  warehouse: WarehouseRef
  status: PurchaseOrderStatus
  currency: string
  total: number
  lineCount: number
  expectedOn: string | null
  createdAt: string
}

export type SalesOrderStatus = 'DRAFT' | 'CONFIRMED' | 'FULFILLED' | 'CANCELLED'

export interface SalesLineView {
  id: string
  lineNo: number
  product: StockProductRef
  quantity: number
  unitPrice: number
  lineTotal: number
}

export interface SalesOrderView {
  id: string
  number: string
  customer: PartyRef | null
  warehouse: WarehouseRef
  status: SalesOrderStatus
  currency: string
  notes: string | null
  lines: SalesLineView[]
  total: number
  confirmedAt: string | null
  fulfilledAt: string | null
  cancelledAt: string | null
  createdBy: MemberRef | null
  createdAt: string
  updatedAt: string
  version: number
}

export interface SalesOrderSummary {
  id: string
  number: string
  customer: PartyRef | null
  warehouse: WarehouseRef
  status: SalesOrderStatus
  currency: string
  total: number
  lineCount: number
  createdAt: string
}

export interface ReorderRuleView {
  id: string
  product: StockProductRef
  warehouse: WarehouseRef
  minQuantity: number
  maxQuantity: number
  supplier: PartyRef | null
  updatedAt: string
  version: number
}

export interface ReorderSuggestion {
  ruleId: string
  product: StockProductRef
  warehouse: WarehouseRef
  available: number
  onOrder: number
  minQuantity: number
  maxQuantity: number
  supplier: PartyRef | null
  usedLast30Days: number
  averageDailyUsage: number
  /** null when nothing was used in the last 30 days */
  daysOfCover: number | null
  suggestedQuantity: number
  explanation: string
}

export interface InventoryOverview {
  belowMinimum: number
  purchaseOrdersAwaitingReceipt: number
  salesOrdersAwaitingFulfilment: number
  recentMovements: MovementView[]
}

export type TicketStatus = 'NEW' | 'OPEN' | 'PENDING' | 'RESOLVED' | 'CLOSED'
export type TicketPriority = 'LOW' | 'NORMAL' | 'HIGH' | 'URGENT'
export type TicketChannel = 'PHONE' | 'EMAIL' | 'WALK_IN' | 'WEB' | 'OTHER'
export type SlaState = 'ON_TRACK' | 'AT_RISK' | 'PAUSED' | 'MET' | 'BREACHED'
export type MessageKind = 'PUBLIC_REPLY' | 'INTERNAL_NOTE' | 'CUSTOMER_MESSAGE'
export type ArticleStatus = 'DRAFT' | 'PUBLISHED' | 'ARCHIVED'

export interface CategoryRef {
  id: string
  name: string
}

export interface CategoryView extends CategoryRef {
  description: string | null
  defaultAssignee: MemberRef | null
  position: number
  archivedAt: string | null
  version: number
}

export interface SlaPolicyView {
  priority: TicketPriority
  firstResponseMinutes: number
  resolutionMinutes: number
  version: number
}

export interface TicketProductRef {
  id: string
  sku: string
  name: string
}

/** Any record the ticket concerns; `label` is null when the viewer may not read that type. */
export interface LinkedRecord {
  type: string
  id: string
  label: string | null
}

export interface SlaView {
  firstResponseDueAt: string
  firstRespondedAt: string | null
  firstResponseState: SlaState
  resolutionDueAt: string
  resolvedAt: string | null
  resolutionState: SlaState
  pausedAt: string | null
}

export interface TicketView {
  id: string
  number: string
  subject: string
  description: string
  requester: PartyRef | null
  product: TicketProductRef | null
  linked: LinkedRecord | null
  category: CategoryRef | null
  priority: TicketPriority
  channel: TicketChannel
  assignee: MemberRef | null
  status: TicketStatus
  sla: SlaView
  resolutionNote: string | null
  reopenCount: number
  closedAt: string | null
  createdBy: MemberRef | null
  createdAt: string
  updatedAt: string
  version: number
}

export interface TicketSummary {
  id: string
  number: string
  subject: string
  requester: PartyRef | null
  category: CategoryRef | null
  priority: TicketPriority
  status: TicketStatus
  assignee: MemberRef | null
  sla: SlaView
  createdAt: string
  updatedAt: string
}

export interface MessageView {
  id: string
  kind: MessageKind
  body: string
  author: MemberRef | null
  /** The address a public reply was emailed to; null when no email was sent. */
  emailedTo: string | null
  createdAt: string
}

/** A posted message and the ticket as it is afterwards (status and SLA may have moved). */
export interface MessagePosted {
  message: MessageView
  ticket: TicketView
}

export interface ArticleView {
  id: string
  title: string
  body: string
  category: CategoryRef | null
  status: ArticleStatus
  author: MemberRef | null
  publishedAt: string | null
  createdAt: string
  updatedAt: string
  version: number
}

export interface ArticleSummary {
  id: string
  title: string
  /** The first 200 characters of the body, on one line. */
  excerpt: string
  category: CategoryRef | null
  status: ArticleStatus
  publishedAt: string | null
  updatedAt: string
}

export interface TicketContext {
  previousTickets: TicketSummary[]
  possibleDuplicates: TicketSummary[]
  suggestedArticles: ArticleSummary[]
}

/** D15. Rates are 0–1; null (or absent) when nothing in the window can be measured. */
export interface HelpDeskDashboard {
  openByStatus: Partial<Record<TicketStatus, number>>
  openByPriority: Partial<Record<TicketPriority, number>>
  unassigned: number
  breached: number
  atRisk: number
  last30Days: {
    created: number
    resolved: number
    averageFirstResponseMinutes?: number | null
    medianFirstResponseMinutes?: number | null
    averageResolutionMinutes?: number | null
    firstResponseMetRate?: number | null
    resolutionMetRate?: number | null
    reopenRate?: number | null
  }
}
