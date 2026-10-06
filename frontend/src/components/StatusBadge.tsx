import { Badge } from '@/components/ui/badge'

const LABELS: Record<
  string,
  { label: string; variant: 'default' | 'secondary' | 'destructive' | 'outline' }
> = {
  ACTIVE: { label: 'Active', variant: 'default' },
  INVITED: { label: 'Invited', variant: 'secondary' },
  DISABLED: { label: 'Disabled', variant: 'destructive' },
  PENDING: { label: 'Pending', variant: 'secondary' },
  ACCEPTED: { label: 'Accepted', variant: 'outline' },
  REVOKED: { label: 'Revoked', variant: 'outline' },
  EXPIRED: { label: 'Expired', variant: 'outline' },
  SUSPENDED: { label: 'Suspended', variant: 'destructive' },
  PENDING_VERIFICATION: { label: 'Pending verification', variant: 'secondary' },
}

export function StatusBadge({ status }: { status: string }) {
  const known = LABELS[status] ?? { label: status, variant: 'outline' as const }
  return <Badge variant={known.variant}>{known.label}</Badge>
}
