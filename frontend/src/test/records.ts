import type {
  ActivityView,
  BoardView,
  CustomerRow,
  CustomerSummary,
  DashboardView,
  DocumentView,
  LeadView,
  OpportunitySummary,
  OpportunityView,
  Page,
  PartySummary,
  PartyView,
  ProductView,
  StageView,
  TaskView,
  InventoryOverview,
  MovementView,
  ProductStock,
  PurchaseOrderSummary,
  PurchaseOrderView,
  ReorderRuleView,
  ReorderSuggestion,
  SalesOrderSummary,
  SalesOrderView,
  StockRow,
  WarehouseView,
} from '@/lib/api/types'

export function pageOf<T>(items: T[], total = items.length, page = 0, size = 20): Page<T> {
  return { items, page, size, total }
}

export function aParty(overrides: Partial<PartyView> = {}): PartyView {
  return {
    id: 'p-acme',
    kind: 'ORGANIZATION',
    name: 'Acme',
    firstName: null,
    lastName: null,
    jobTitle: null,
    organization: null,
    email: 'sales@acme.test',
    phone: null,
    domain: 'acme.test',
    website: null,
    roles: [],
    duplicateReason: null,
    archivedAt: null,
    createdAt: '2026-10-01T09:00:00Z',
    updatedAt: '2026-10-01T09:00:00Z',
    version: 0,
    ...overrides,
  }
}

export function aPerson(overrides: Partial<PartyView> = {}): PartyView {
  return aParty({
    id: 'p-grace',
    kind: 'PERSON',
    name: 'Grace Hopper',
    firstName: 'Grace',
    lastName: 'Hopper',
    email: 'grace@acme.test',
    domain: null,
    ...overrides,
  })
}

export function aSummary(overrides: Partial<PartySummary> = {}): PartySummary {
  return {
    id: 'p-acme',
    kind: 'ORGANIZATION',
    name: 'Acme',
    email: 'sales@acme.test',
    phone: null,
    domain: 'acme.test',
    organization: null,
    roles: [],
    archived: false,
    ...overrides,
  }
}

export function aProduct(overrides: Partial<ProductView> = {}): ProductView {
  return {
    id: 'pr-widget',
    sku: 'W-1',
    name: 'Widget',
    description: null,
    kind: 'GOODS',
    unit: 'each',
    listPrice: 12.5,
    currency: 'USD',
    archivedAt: null,
    createdAt: '2026-10-01T09:00:00Z',
    updatedAt: '2026-10-01T09:00:00Z',
    version: 0,
    ...overrides,
  }
}

export function aTask(overrides: Partial<TaskView> = {}): TaskView {
  return {
    id: 't-1',
    title: 'Send quote',
    description: null,
    status: 'OPEN',
    priority: 'NORMAL',
    dueOn: null,
    assignee: { id: 'u-ada', name: 'Ada Lovelace' },
    subject: null,
    createdBy: { id: 'u-ada', name: 'Ada Lovelace' },
    completedAt: null,
    createdAt: '2026-10-01T09:00:00Z',
    updatedAt: '2026-10-01T09:00:00Z',
    version: 0,
    ...overrides,
  }
}

export function anActivity(overrides: Partial<ActivityView> = {}): ActivityView {
  return {
    id: 'a-1',
    subjectType: 'PARTY',
    subjectId: 'p-acme',
    type: 'NOTE',
    summary: 'Kick-off call booked',
    body: 'Thursday 10:00',
    occurredAt: '2026-10-05T10:00:00Z',
    author: { id: 'u-ada', name: 'Ada Lovelace' },
    createdAt: '2026-10-05T10:00:00Z',
    subject: null,
    ...overrides,
  }
}

export function aDocument(overrides: Partial<DocumentView> = {}): DocumentView {
  return {
    id: 'd-1',
    subjectType: 'PARTY',
    subjectId: 'p-acme',
    fileName: 'contract.pdf',
    contentType: 'application/pdf',
    sizeBytes: 2048,
    sha256: 'a'.repeat(64),
    uploadedBy: { id: 'u-ada', name: 'Ada Lovelace' },
    createdAt: '2026-10-05T10:00:00Z',
    ...overrides,
  }
}

export function aStage(overrides: Partial<StageView> = {}): StageView {
  return {
    id: 's-prospecting',
    name: 'Prospecting',
    probability: 10,
    kind: 'OPEN',
    position: 0,
    version: 0,
    ...overrides,
  }
}

export function defaultStages(): StageView[] {
  return [
    aStage(),
    aStage({ id: 's-qualification', name: 'Qualification', probability: 25, position: 1 }),
    aStage({ id: 's-proposal', name: 'Proposal', probability: 50, position: 2 }),
    aStage({ id: 's-negotiation', name: 'Negotiation', probability: 75, position: 3 }),
    aStage({ id: 's-won', name: 'Won', probability: 100, kind: 'WON', position: 0 }),
    aStage({ id: 's-lost', name: 'Lost', probability: 0, kind: 'LOST', position: 0 }),
  ]
}

export function aLead(overrides: Partial<LeadView> = {}): LeadView {
  return {
    id: 'l-grace',
    name: 'Grace Hopper',
    firstName: 'Grace',
    lastName: 'Hopper',
    companyName: 'Acme Robotics',
    jobTitle: null,
    email: 'grace@acme.test',
    phone: null,
    source: 'REFERRAL',
    status: 'NEW',
    owner: { id: 'u-ada', name: 'Ada Lovelace' },
    estimatedValue: 5000,
    currency: 'USD',
    description: null,
    disqualifyReason: null,
    disqualifiedAt: null,
    convertedAt: null,
    convertedPerson: null,
    convertedOrganization: null,
    convertedOpportunityId: null,
    createdBy: { id: 'u-ada', name: 'Ada Lovelace' },
    createdAt: '2026-10-01T09:00:00Z',
    updatedAt: '2026-10-01T09:00:00Z',
    version: 0,
    ...overrides,
  }
}

export function anOpportunity(overrides: Partial<OpportunityView> = {}): OpportunityView {
  return {
    id: 'o-renewal',
    name: 'Packaging renewal',
    account: { id: 'p-acme', name: 'Acme' },
    contact: { id: 'p-grace', name: 'Grace Hopper' },
    stage: { id: 's-prospecting', name: 'Prospecting', kind: 'OPEN', probability: 10 },
    status: 'OPEN',
    amount: 1200,
    currency: 'USD',
    expectedCloseOn: '2026-11-30',
    owner: { id: 'u-ada', name: 'Ada Lovelace' },
    leadId: null,
    description: null,
    lostReason: null,
    closedAt: null,
    createdBy: { id: 'u-ada', name: 'Ada Lovelace' },
    createdAt: '2026-10-01T09:00:00Z',
    updatedAt: '2026-10-01T09:00:00Z',
    version: 0,
    ...overrides,
  }
}

export function anOpportunitySummary(
  overrides: Partial<OpportunitySummary> = {},
): OpportunitySummary {
  const o = anOpportunity()
  return {
    id: o.id,
    name: o.name,
    account: o.account,
    stage: o.stage,
    amount: o.amount,
    currency: o.currency,
    expectedCloseOn: o.expectedCloseOn,
    owner: o.owner,
    version: o.version,
    ...overrides,
  }
}

export function aBoard(): BoardView {
  return {
    columns: defaultStages().map((stage, index) => ({
      stage,
      count: index === 0 ? 1 : 0,
      totals: index === 0 ? [{ currency: 'USD', amount: 1200 }] : [],
      weighted: index === 0 ? [{ currency: 'USD', amount: 120 }] : [],
      opportunities: index === 0 ? [anOpportunitySummary()] : [],
    })),
  }
}

export function aCustomerRow(overrides: Partial<CustomerRow> = {}): CustomerRow {
  return {
    party: aSummary({ roles: ['CUSTOMER'] }),
    openCount: 1,
    openValue: [{ currency: 'USD', amount: 1200 }],
    wonCount: 2,
    wonValue: [
      { currency: 'EUR', amount: 50 },
      { currency: 'USD', amount: 300 },
    ],
    ...overrides,
  }
}

export function aCustomerSummary(overrides: Partial<CustomerSummary> = {}): CustomerSummary {
  return {
    party: aParty({
      roles: [{ role: 'CUSTOMER', status: 'ACTIVE', since: null, employeeNumber: null }],
    }),
    openCount: 1,
    openValue: [{ currency: 'USD', amount: 1200 }],
    weightedValue: [{ currency: 'USD', amount: 120 }],
    wonCount: 2,
    wonValue: [{ currency: 'USD', amount: 300 }],
    lostCount: 1,
    leadCount: 1,
    ...overrides,
  }
}

export function aDashboard(overrides: Partial<DashboardView> = {}): DashboardView {
  const stages = defaultStages().filter((s) => s.kind === 'OPEN')
  return {
    leads: {
      open: { NEW: 3, CONTACTED: 2, QUALIFIED: 1 },
      newLast30Days: 6,
      converted90Days: 1,
      disqualified90Days: 3,
      conversionRate: 0.25,
    },
    pipeline: {
      stages: stages.map((stage, index) => ({
        stage,
        count: index === 0 ? 2 : 0,
        totals: index === 0 ? [{ currency: 'USD', amount: 1200 }] : [],
        weighted: index === 0 ? [{ currency: 'USD', amount: 120 }] : [],
      })),
      wonThisMonth: { count: 1, totals: [{ currency: 'USD', amount: 300 }] },
      lostThisMonth: { count: 1, totals: [] },
      closingSoon: [anOpportunitySummary()],
    },
    ...overrides,
  }
}

export function aWarehouse(overrides: Partial<WarehouseView> = {}): WarehouseView {
  return {
    id: 'w-main',
    code: 'MAIN',
    name: 'Main warehouse',
    address: null,
    archivedAt: null,
    version: 0,
    ...overrides,
  }
}

const WIDGET = { id: 'pr-widget', sku: 'W-1', name: 'Widget', unit: 'each' }
const MAIN = { id: 'w-main', code: 'MAIN', name: 'Main warehouse' }
const SUPPLIER = { id: 'p-konkan', name: 'Konkan Supplies' }
const CUSTOMER = { id: 'p-deccan', name: 'Deccan Retail' }

export function aProductStock(overrides: Partial<ProductStock> = {}): ProductStock {
  return {
    product: WIDGET,
    levels: [{ warehouse: MAIN, onHand: 12, reserved: 2, available: 10 }],
    onHand: 12,
    reserved: 2,
    available: 10,
    ...overrides,
  }
}

export function aMovement(overrides: Partial<MovementView> = {}): MovementView {
  return {
    id: 'm-1',
    product: WIDGET,
    warehouse: MAIN,
    kind: 'RECEIPT',
    quantity: 10,
    onHandAfter: 12,
    referenceType: 'PURCHASE_ORDER',
    referenceId: 'po-1',
    reason: 'PO-00001',
    actor: { id: 'u-ada', name: 'Ada Lovelace' },
    occurredAt: '2026-10-08T09:00:00Z',
    ...overrides,
  }
}

export function aStockRow(overrides: Partial<StockRow> = {}): StockRow {
  return {
    product: WIDGET,
    warehouse: MAIN,
    onHand: 12,
    reserved: 2,
    available: 10,
    onOrder: 0,
    ruleId: 'rr-1',
    minQuantity: 20,
    maxQuantity: 60,
    belowMin: true,
    ...overrides,
  }
}

export function aPurchaseOrder(overrides: Partial<PurchaseOrderView> = {}): PurchaseOrderView {
  return {
    id: 'po-1',
    number: 'PO-00001',
    supplier: SUPPLIER,
    warehouse: MAIN,
    status: 'DRAFT',
    currency: 'USD',
    expectedOn: null,
    notes: null,
    lines: [
      {
        id: 'pl-1',
        lineNo: 1,
        product: WIDGET,
        quantity: 10,
        receivedQuantity: 0,
        remainingQuantity: 10,
        unitCost: 2.5,
        lineTotal: 25,
      },
    ],
    total: 25,
    orderedAt: null,
    receivedAt: null,
    cancelledAt: null,
    createdBy: { id: 'u-ada', name: 'Ada Lovelace' },
    createdAt: '2026-10-08T09:00:00Z',
    updatedAt: '2026-10-08T09:00:00Z',
    version: 0,
    ...overrides,
  }
}

export function aPurchaseSummary(
  overrides: Partial<PurchaseOrderSummary> = {},
): PurchaseOrderSummary {
  return {
    id: 'po-1',
    number: 'PO-00001',
    supplier: SUPPLIER,
    warehouse: MAIN,
    status: 'DRAFT',
    currency: 'USD',
    total: 25,
    lineCount: 1,
    expectedOn: null,
    createdAt: '2026-10-08T09:00:00Z',
    ...overrides,
  }
}

export function aSalesOrder(overrides: Partial<SalesOrderView> = {}): SalesOrderView {
  return {
    id: 'so-1',
    number: 'SO-00001',
    customer: CUSTOMER,
    warehouse: MAIN,
    status: 'DRAFT',
    currency: 'USD',
    notes: null,
    lines: [
      { id: 'sl-1', lineNo: 1, product: WIDGET, quantity: 4, unitPrice: 12.5, lineTotal: 50 },
    ],
    total: 50,
    confirmedAt: null,
    fulfilledAt: null,
    cancelledAt: null,
    createdBy: { id: 'u-ada', name: 'Ada Lovelace' },
    createdAt: '2026-10-08T09:00:00Z',
    updatedAt: '2026-10-08T09:00:00Z',
    version: 0,
    ...overrides,
  }
}

export function aSalesSummary(overrides: Partial<SalesOrderSummary> = {}): SalesOrderSummary {
  return {
    id: 'so-1',
    number: 'SO-00001',
    customer: CUSTOMER,
    warehouse: MAIN,
    status: 'DRAFT',
    currency: 'USD',
    total: 50,
    lineCount: 1,
    createdAt: '2026-10-08T09:00:00Z',
    ...overrides,
  }
}

export function aReorderRule(overrides: Partial<ReorderRuleView> = {}): ReorderRuleView {
  return {
    id: 'rr-1',
    product: WIDGET,
    warehouse: MAIN,
    minQuantity: 20,
    maxQuantity: 60,
    supplier: SUPPLIER,
    updatedAt: '2026-10-08T09:00:00Z',
    version: 0,
    ...overrides,
  }
}

export function aSuggestion(overrides: Partial<ReorderSuggestion> = {}): ReorderSuggestion {
  return {
    ruleId: 'rr-1',
    product: WIDGET,
    warehouse: MAIN,
    available: 12,
    onOrder: 0,
    minQuantity: 20,
    maxQuantity: 60,
    supplier: SUPPLIER,
    usedLast30Days: 38,
    averageDailyUsage: 1.2667,
    daysOfCover: 9.5,
    suggestedQuantity: 48,
    explanation:
      '12 available, 0 on order, below the minimum of 20; 38 used in the last 30 days (about 9 days of cover); order 48 to reach 60',
    ...overrides,
  }
}

export function anOverview(overrides: Partial<InventoryOverview> = {}): InventoryOverview {
  return {
    belowMinimum: 2,
    purchaseOrdersAwaitingReceipt: 1,
    salesOrdersAwaitingFulfilment: 3,
    recentMovements: [aMovement()],
    ...overrides,
  }
}
