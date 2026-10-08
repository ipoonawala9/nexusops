import type { LeadSource, LeadStatus, OpportunityStatus } from '@/lib/api/types'

export const LEAD_STATUS_LABELS: Record<LeadStatus, string> = {
  NEW: 'New',
  CONTACTED: 'Contacted',
  QUALIFIED: 'Qualified',
  DISQUALIFIED: 'Disqualified',
  CONVERTED: 'Converted',
}

export const OPEN_LEAD_STATUSES: LeadStatus[] = ['NEW', 'CONTACTED', 'QUALIFIED']

export const LEAD_SOURCE_LABELS: Record<LeadSource, string> = {
  WEBSITE: 'Website',
  REFERRAL: 'Referral',
  WALK_IN: 'Walk-in',
  PHONE: 'Phone',
  EMAIL: 'Email',
  SOCIAL: 'Social media',
  EVENT: 'Event',
  OTHER: 'Other',
}

export const OPPORTUNITY_STATUS_LABELS: Record<OpportunityStatus, string> = {
  OPEN: 'Open',
  WON: 'Won',
  LOST: 'Lost',
}
