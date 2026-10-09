import type {
  ArticleStatus,
  MessageKind,
  SlaState,
  TicketChannel,
  TicketPriority,
  TicketStatus,
} from '@/lib/api/types'

export const TICKET_STATUS_LABELS: Record<TicketStatus, string> = {
  NEW: 'New',
  OPEN: 'Open',
  PENDING: 'Waiting on customer',
  RESOLVED: 'Resolved',
  CLOSED: 'Closed',
}

export const OPEN_STATUSES: TicketStatus[] = ['NEW', 'OPEN', 'PENDING']

export const PRIORITY_LABELS: Record<TicketPriority, string> = {
  LOW: 'Low',
  NORMAL: 'Normal',
  HIGH: 'High',
  URGENT: 'Urgent',
}

/** Most urgent first. */
export const PRIORITIES: TicketPriority[] = ['URGENT', 'HIGH', 'NORMAL', 'LOW']

export const CHANNEL_LABELS: Record<TicketChannel, string> = {
  PHONE: 'Phone',
  EMAIL: 'Email',
  WALK_IN: 'Walk-in',
  WEB: 'Web',
  OTHER: 'Other',
}

export const SLA_STATE_LABELS: Record<SlaState, string> = {
  ON_TRACK: 'On track',
  AT_RISK: 'At risk',
  PAUSED: 'Paused',
  MET: 'Met',
  BREACHED: 'Breached',
}

export const MESSAGE_KIND_LABELS: Record<MessageKind, string> = {
  PUBLIC_REPLY: 'Reply to customer',
  INTERNAL_NOTE: 'Internal note',
  CUSTOMER_MESSAGE: 'Customer message',
}

export const ARTICLE_STATUS_LABELS: Record<ArticleStatus, string> = {
  DRAFT: 'Draft',
  PUBLISHED: 'Published',
  ARCHIVED: 'Archived',
}
