import { useState } from 'react'
import { ConfirmDialog } from '@/components/ConfirmDialog'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Textarea } from '@/components/ui/textarea'
import { describedBy, Field } from '@/components/form/Field'
import { FormError } from '@/components/form/FormError'
import { PERMISSIONS, useCan } from '@/features/auth/permissions'
import { useTenantSession } from '@/features/auth/tenantSession'
import type { TicketStatus, TicketView } from '@/lib/api/types'
import { AgentSelect } from './AgentSelect'
import { TicketFormDialog } from './TicketFormDialog'
import { useTicketAction } from './useTicketAction'

type DialogName = 'edit' | 'assign' | 'resolve' | 'close' | null

/** Edit, assign and move a ticket (D6, D7). The buttons follow the user's permissions and the status. */
export function TicketActions({ ticket }: { ticket: TicketView }) {
  const can = useCan()
  const session = useTenantSession()
  const me = session.state.status === 'authenticated' ? session.state.profile.user : null
  const action = useTicketAction(ticket.id)
  const [dialog, setDialog] = useState<DialogName>(null)
  const path = `/helpdesk/tickets/${ticket.id}`
  const open = ticket.status === 'NEW' || ticket.status === 'OPEN' || ticket.status === 'PENDING'
  const closed = ticket.status === 'CLOSED'
  const canManage = can(PERMISSIONS.ticketManage)
  const canAssign = can(PERMISSIONS.ticketAssign)
  const canResolve = can(PERMISSIONS.ticketResolve)

  function show(next: DialogName) {
    action.setError(null)
    setDialog(next)
  }

  function move(status: TicketStatus, success: string) {
    return action.run(`${path}/status`, { status, note: null, version: ticket.version }, success)
  }

  if (closed) return null
  return (
    <div className="space-y-2">
      <div className="flex flex-wrap gap-2">
        {canManage && (
          <Button variant="outline" size="sm" onClick={() => show('edit')}>
            Edit
          </Button>
        )}
        {canAssign && (
          <>
            {me && ticket.assignee?.id !== me.id && (
              <Button
                variant="outline"
                size="sm"
                disabled={action.busy}
                onClick={() =>
                  void action.run(
                    `${path}/assign`,
                    { assigneeId: me.id, version: ticket.version },
                    `${ticket.number} is yours.`,
                  )
                }
              >
                Assign to me
              </Button>
            )}
            <Button variant="outline" size="sm" onClick={() => show('assign')}>
              Assign…
            </Button>
          </>
        )}
        {canResolve && (
          <>
            {ticket.status === 'NEW' && (
              <Button
                variant="outline"
                size="sm"
                disabled={action.busy}
                onClick={() => void move('OPEN', `${ticket.number} is open.`)}
              >
                Start work
              </Button>
            )}
            {(ticket.status === 'NEW' || ticket.status === 'OPEN') && (
              <Button
                variant="outline"
                size="sm"
                disabled={action.busy}
                onClick={() => void move('PENDING', `${ticket.number} is waiting on the customer.`)}
              >
                Wait on customer
              </Button>
            )}
            {ticket.status === 'PENDING' && (
              <Button
                variant="outline"
                size="sm"
                disabled={action.busy}
                onClick={() => void move('OPEN', `${ticket.number} is open again.`)}
              >
                Resume
              </Button>
            )}
            {open && (
              <Button size="sm" onClick={() => show('resolve')}>
                Resolve…
              </Button>
            )}
            {ticket.status === 'RESOLVED' && (
              <>
                <Button
                  variant="outline"
                  size="sm"
                  disabled={action.busy}
                  onClick={() => void move('OPEN', `${ticket.number} reopened.`)}
                >
                  Reopen
                </Button>
                <Button variant="outline" size="sm" onClick={() => show('close')}>
                  Close
                </Button>
              </>
            )}
          </>
        )}
      </div>
      {!dialog && <FormError message={action.error} />}
      {dialog === 'edit' && (
        <TicketFormDialog
          ticket={ticket}
          onClose={() => setDialog(null)}
          onSaved={() => setDialog(null)}
        />
      )}
      {dialog === 'assign' && (
        <AssignDialog
          ticket={ticket}
          busy={action.busy}
          error={action.error}
          onClose={() => show(null)}
          onAssign={(assigneeId) =>
            action
              .run(
                `${path}/assign`,
                { assigneeId: assigneeId || null, version: ticket.version },
                assigneeId ? 'Ticket assigned.' : 'Ticket unassigned.',
              )
              .then((ok) => ok && setDialog(null))
          }
        />
      )}
      {dialog === 'resolve' && (
        <ResolveDialog
          ticket={ticket}
          busy={action.busy}
          error={action.error}
          onClose={() => show(null)}
          onResolve={(note) =>
            action
              .run(
                `${path}/status`,
                { status: 'RESOLVED', note, version: ticket.version },
                `${ticket.number} resolved.`,
              )
              .then((ok) => ok && setDialog(null))
          }
        />
      )}
      <ConfirmDialog
        open={dialog === 'close'}
        title={`Close ${ticket.number}?`}
        description="A closed ticket can't be reopened or changed. Its history stays here."
        confirmLabel="Close ticket"
        busy={action.busy}
        error={action.error}
        onCancel={() => show(null)}
        onConfirm={() =>
          void move('CLOSED', `${ticket.number} closed.`).then((ok) => ok && setDialog(null))
        }
      />
    </div>
  )
}

function AssignDialog({
  ticket,
  busy,
  error,
  onClose,
  onAssign,
}: {
  ticket: TicketView
  busy: boolean
  error: string | null
  onClose: () => void
  onAssign: (assigneeId: string) => Promise<unknown>
}) {
  const [value, setValue] = useState(ticket.assignee?.id ?? '')
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Assign {ticket.number}</DialogTitle>
          <DialogDescription>
            Whoever you choose gets an email with a link to the ticket.
          </DialogDescription>
        </DialogHeader>
        <AgentSelect
          id="field-assigneeId"
          label="Assignee"
          value={value}
          current={ticket.assignee}
          onChange={setValue}
        />
        <FormError message={error} />
        <DialogFooter>
          <Button type="button" variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button disabled={busy} onClick={() => void onAssign(value)}>
            Assign
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

function ResolveDialog({
  ticket,
  busy,
  error,
  onClose,
  onResolve,
}: {
  ticket: TicketView
  busy: boolean
  error: string | null
  onClose: () => void
  onResolve: (note: string) => Promise<unknown>
}) {
  const [note, setNote] = useState('')
  const [noteError, setNoteError] = useState<string | undefined>()
  const id = 'field-resolutionNote'
  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Resolve {ticket.number}</DialogTitle>
          <DialogDescription>
            Say what fixed it. The next person with the same problem will thank you.
          </DialogDescription>
        </DialogHeader>
        <Field id={id} label="Resolution note" error={noteError}>
          <Textarea
            id={id}
            rows={4}
            maxLength={2000}
            value={note}
            aria-invalid={noteError ? true : undefined}
            aria-describedby={describedBy(id, noteError)}
            onChange={(e) => setNote(e.target.value)}
          />
        </Field>
        <FormError message={error} />
        <DialogFooter>
          <Button type="button" variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button
            disabled={busy}
            onClick={() => {
              if (!note.trim()) {
                setNoteError('Add a resolution note.')
                return
              }
              setNoteError(undefined)
              void onResolve(note.trim())
            }}
          >
            Resolve
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
