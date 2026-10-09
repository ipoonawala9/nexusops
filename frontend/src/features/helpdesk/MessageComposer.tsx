import { useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Textarea } from '@/components/ui/textarea'
import { describedBy, Field } from '@/components/form/Field'
import { FormError } from '@/components/form/FormError'
import { NativeSelect } from '@/components/form/NativeSelect'
import { useApi } from '@/lib/api/ApiContext'
import { problemMessage } from '@/lib/api/problems'
import type { MessageKind, MessagePosted, TicketView } from '@/lib/api/types'
import { invalidateHelpDesk } from './invalidation'
import { MESSAGE_KIND_LABELS } from './labels'

const SUBMIT: Record<MessageKind, string> = {
  PUBLIC_REPLY: 'Send reply',
  INTERNAL_NOTE: 'Add note',
  CUSTOMER_MESSAGE: 'Log customer message',
}
const HINTS: Record<MessageKind, string> = {
  PUBLIC_REPLY: 'Emailed to the requester. The first reply is the first response.',
  INTERNAL_NOTE: 'Only your team sees notes. Nothing is emailed.',
  CUSTOMER_MESSAGE:
    'Something the customer told you by phone or in person. A ticket waiting on the customer opens again.',
}

/** D9. `kind` and `body` live in the page so a suggested article can be inserted into the reply. */
export function MessageComposer({
  ticket,
  kind,
  onKind,
  body,
  onBody,
}: {
  ticket: TicketView
  kind: MessageKind
  onKind: (kind: MessageKind) => void
  body: string
  onBody: (body: string) => void
}) {
  const api = useApi()
  const queryClient = useQueryClient()
  const [busy, setBusy] = useState(false)
  const [bodyError, setBodyError] = useState<string | undefined>()
  const [error, setError] = useState<string | null>(null)
  const id = 'field-message'

  async function send() {
    if (!body.trim()) {
      setBodyError('Write a message.')
      return
    }
    setBodyError(undefined)
    setError(null)
    setBusy(true)
    try {
      const posted = await api.post<MessagePosted>(`/helpdesk/tickets/${ticket.id}/messages`, {
        kind,
        body: body.trim(),
      })
      queryClient.setQueryData(['helpdesk', 'ticket', ticket.id], posted.ticket)
      await invalidateHelpDesk(queryClient)
      onBody('')
      if (kind === 'PUBLIC_REPLY')
        toast.success(
          posted.message.emailedTo
            ? `Reply emailed to ${posted.message.emailedTo}.`
            : 'Reply saved. No email was sent: the requester has no email address.',
        )
      else toast.success(kind === 'INTERNAL_NOTE' ? 'Note added.' : 'Customer message logged.')
    } catch (e) {
      setError(problemMessage(e))
      void queryClient.invalidateQueries({ queryKey: ['helpdesk', 'ticket', ticket.id] })
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="space-y-3">
      <Field id="field-messageKind" label="Message type">
        <NativeSelect
          id="field-messageKind"
          value={kind}
          onChange={(e) => onKind(e.target.value as MessageKind)}
        >
          {(Object.keys(MESSAGE_KIND_LABELS) as MessageKind[]).map((k) => (
            <option key={k} value={k}>
              {MESSAGE_KIND_LABELS[k]}
            </option>
          ))}
        </NativeSelect>
      </Field>
      <Field id={id} label="Message" error={bodyError} hint={HINTS[kind]}>
        <Textarea
          id={id}
          rows={5}
          maxLength={10000}
          value={body}
          aria-invalid={bodyError ? true : undefined}
          aria-describedby={describedBy(id, bodyError, HINTS[kind])}
          onChange={(e) => onBody(e.target.value)}
        />
      </Field>
      <FormError message={error} />
      <Button disabled={busy} onClick={() => void send()}>
        {SUBMIT[kind]}
      </Button>
    </div>
  )
}
