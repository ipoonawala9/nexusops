import type { SlaState, SlaView, TicketStatus } from '@/lib/api/types'

/** 90 → "1 h 30 min", 1500 → "1 d 1 h": the two largest units that apply. */
export function formatMinutes(minutes: number): string {
  const total = Math.max(0, Math.round(minutes))
  const days = Math.floor(total / 1440)
  const hours = Math.floor((total % 1440) / 60)
  const mins = total % 60
  const parts = [
    days > 0 ? `${days} d` : null,
    hours > 0 ? `${hours} h` : null,
    mins > 0 && days === 0 ? `${mins} min` : null,
  ].filter((part): part is string => part !== null)
  return parts.length > 0 ? parts.join(' ') : '0 min'
}

/** 0.5 → "50%"; nothing to measure → "—". */
export function formatRate(rate: number | null | undefined): string {
  return rate == null ? '—' : `${Math.round(rate * 100)}%`
}

/**
 * The target that matters now: the first response until someone has replied, then the resolution. An open ticket
 * whose first response came late stays badged as breached (as the list's breached filter counts it) until its
 * resolution is the breached one.
 */
export function currentTarget(ticket: { status: TicketStatus; sla: SlaView }): {
  target: 'First response' | 'Resolution'
  state: SlaState
  due: string
} {
  const { sla, status } = ticket
  const open = status !== 'RESOLVED' && status !== 'CLOSED'
  const firstResponse = {
    target: 'First response' as const,
    state: sla.firstResponseState,
    due: sla.firstResponseDueAt,
  }
  if (open && sla.firstRespondedAt === null) return firstResponse
  if (open && sla.firstResponseState === 'BREACHED' && sla.resolutionState !== 'BREACHED')
    return firstResponse
  return { target: 'Resolution', state: sla.resolutionState, due: sla.resolutionDueAt }
}
