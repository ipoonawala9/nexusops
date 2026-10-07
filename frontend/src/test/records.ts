import type {
  ActivityView,
  DocumentView,
  Page,
  PartySummary,
  PartyView,
  ProductView,
  TaskView,
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
