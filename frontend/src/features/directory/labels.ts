import type { PartyKind, PartyRoleType } from '@/lib/api/types'

export const KIND_LABELS: Record<PartyKind, string> = { PERSON: 'Person', ORGANIZATION: 'Organization' }

export const ROLE_LABELS: Record<PartyRoleType, string> = {
  CUSTOMER: 'Customer',
  SUPPLIER: 'Supplier',
  EMPLOYEE: 'Employee',
}
