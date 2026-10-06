import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { ConfirmDialog } from '@/components/ConfirmDialog'
import { StatusBadge } from '@/components/StatusBadge'
import { EmptyState, ErrorState, ListSkeleton } from '@/components/states'
import { PERMISSIONS, useCan } from '@/features/auth/permissions'
import { useApi } from '@/lib/api/ApiContext'
import { problemMessage } from '@/lib/api/problems'
import type { InvitationView } from '@/lib/api/types'
import { formatDateTime } from '@/lib/format'
import { InviteDialog } from './InviteDialog'

export function InvitationsPanel() {
  const api = useApi()
  const queryClient = useQueryClient()
  const canInvite = useCan()(PERMISSIONS.userInvite)
  const [inviting, setInviting] = useState(false)
  const [revoking, setRevoking] = useState<InvitationView | null>(null)
  const [busy, setBusy] = useState(false)
  const [revokeError, setRevokeError] = useState<string | null>(null)
  const invitations = useQuery({
    queryKey: ['invitations'],
    queryFn: () => api.get<InvitationView[]>('/invitations'),
  })

  async function revoke() {
    if (!revoking) return
    setBusy(true)
    setRevokeError(null)
    try {
      await api.del(`/invitations/${revoking.id}`)
      toast.success('Invitation revoked.')
      setRevoking(null)
      await queryClient.invalidateQueries({ queryKey: ['invitations'] })
    } catch (error) {
      setRevokeError(problemMessage(error))
    } finally {
      setBusy(false)
    }
  }

  return (
    <section aria-labelledby="invitations-heading" className="mt-10">
      <div className="mb-3 flex items-center justify-between">
        <h2 id="invitations-heading" className="text-lg font-semibold">
          Invitations
        </h2>
        {canInvite && <Button onClick={() => setInviting(true)}>Invite people</Button>}
      </div>
      {invitations.isPending ? (
        <ListSkeleton rows={3} />
      ) : invitations.isError ? (
        <ErrorState error={invitations.error} onRetry={() => void invitations.refetch()} />
      ) : invitations.data.length === 0 ? (
        <EmptyState
          title="No invitations yet."
          description="Invite teammates to give them access to this workspace."
        />
      ) : (
        <div className="overflow-x-auto rounded-lg border">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Email</TableHead>
                <TableHead>Role</TableHead>
                <TableHead>Status</TableHead>
                <TableHead>Expires</TableHead>
                {canInvite && (
                  <TableHead>
                    <span className="sr-only">Actions</span>
                  </TableHead>
                )}
              </TableRow>
            </TableHeader>
            <TableBody>
              {invitations.data.map((invitation) => (
                <TableRow key={invitation.id}>
                  <TableCell>{invitation.email}</TableCell>
                  <TableCell>{invitation.roleName}</TableCell>
                  <TableCell>
                    <StatusBadge status={invitation.status} />
                  </TableCell>
                  <TableCell>{formatDateTime(invitation.expiresAt)}</TableCell>
                  {canInvite && (
                    <TableCell className="text-right">
                      {invitation.status === 'PENDING' && (
                        <Button
                          variant="ghost"
                          size="sm"
                          aria-label={`Revoke invitation for ${invitation.email}`}
                          onClick={() => {
                            setRevokeError(null)
                            setRevoking(invitation)
                          }}
                        >
                          Revoke
                        </Button>
                      )}
                    </TableCell>
                  )}
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      )}
      {inviting && <InviteDialog onClose={() => setInviting(false)} />}
      <ConfirmDialog
        open={revoking !== null}
        title="Revoke invitation?"
        description={
          revoking ? `${revoking.email} won't be able to use their invitation link.` : ''
        }
        confirmLabel="Revoke"
        onConfirm={() => void revoke()}
        onCancel={() => setRevoking(null)}
        busy={busy}
        error={revokeError}
      />
    </section>
  )
}
