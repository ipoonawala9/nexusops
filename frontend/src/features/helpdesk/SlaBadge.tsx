import { Badge } from '@/components/ui/badge'
import type { SlaState } from '@/lib/api/types'
import { formatDateTime } from '@/lib/format'
import { SLA_STATE_LABELS } from './labels'

const VARIANTS: Record<SlaState, 'default' | 'secondary' | 'destructive' | 'outline'> = {
  BREACHED: 'destructive',
  AT_RISK: 'default',
  ON_TRACK: 'secondary',
  PAUSED: 'outline',
  MET: 'outline',
}

/** "First response: At risk", with the due time on hover. */
export function SlaBadge({
  state,
  due,
  target,
}: {
  state: SlaState
  due?: string
  target?: string
}) {
  const text = `${target ? `${target}: ` : ''}${SLA_STATE_LABELS[state]}`
  return (
    <Badge variant={VARIANTS[state]} title={due ? `Due ${formatDateTime(due)}` : undefined}>
      {text}
    </Badge>
  )
}
