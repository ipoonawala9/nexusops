import { zodResolver } from '@hookform/resolvers/zod'
import { useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { toast } from 'sonner'
import { z } from 'zod'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Field } from '@/components/form/Field'
import { FormError } from '@/components/form/FormError'
import { NativeSelect } from '@/components/form/NativeSelect'
import { TextAreaField } from '@/components/form/TextAreaField'
import { TextField } from '@/components/form/TextField'
import { requiredText } from '@/features/auth/schemas'
import { PartyPicker } from '@/features/records/PartyPicker'
import { useApi } from '@/lib/api/ApiContext'
import { ApiError } from '@/lib/api/client'
import { applyFieldErrors, problemMessage } from '@/lib/api/problems'
import type { PartyRef, TicketChannel, TicketPriority, TicketView } from '@/lib/api/types'
import { AgentSelect } from './AgentSelect'
import { CatalogProductPicker } from './CatalogProductPicker'
import { CategorySelect } from './CategorySelect'
import { invalidateHelpDesk } from './invalidation'
import { CHANNEL_LABELS, PRIORITIES, PRIORITY_LABELS } from './labels'
import { LinkedRecordPicker } from './LinkedRecordPicker'

const schema = z.object({
  subject: requiredText(200),
  description: requiredText(10000),
  requesterId: z.string().min(1, 'Choose who the ticket is for.'),
  productId: z.string(),
  linked: z.string(),
  categoryId: z.string(),
  priority: z.enum(['LOW', 'NORMAL', 'HIGH', 'URGENT']),
  channel: z.enum(['PHONE', 'EMAIL', 'WALK_IN', 'WEB', 'OTHER']),
  assigneeId: z.string(),
})
type Values = z.infer<typeof schema>
const FIELDS = [
  'subject',
  'description',
  'requesterId',
  'productId',
  'categoryId',
  'priority',
  'channel',
  'assigneeId',
] as const

function splitLinked(value: string): { linkedType: string | null; linkedId: string | null } {
  const at = value.indexOf(':')
  return at < 0
    ? { linkedType: null, linkedId: null }
    : { linkedType: value.slice(0, at), linkedId: value.slice(at + 1) }
}

/** Create a ticket (D3; may pick an assignee — blank uses the category's default) or edit its details. */
export function TicketFormDialog({
  ticket,
  requester,
  onClose,
  onSaved,
}: {
  ticket?: TicketView
  requester?: PartyRef
  onClose: () => void
  onSaved: (saved: TicketView) => void
}) {
  const api = useApi()
  const queryClient = useQueryClient()
  const [formError, setFormError] = useState<string | null>(null)
  const form = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: {
      subject: ticket?.subject ?? '',
      description: ticket?.description ?? '',
      requesterId: ticket?.requester?.id ?? requester?.id ?? '',
      productId: ticket?.product?.id ?? '',
      linked: ticket?.linked ? `${ticket.linked.type}:${ticket.linked.id}` : '',
      categoryId: ticket?.category?.id ?? '',
      priority: ticket?.priority ?? 'NORMAL',
      channel: ticket?.channel ?? 'PHONE',
      assigneeId: '',
    },
  })
  const errors = form.formState.errors
  const revalidate = { shouldValidate: form.formState.isSubmitted }

  const onSubmit = form.handleSubmit(async (values) => {
    setFormError(null)
    const body = {
      subject: values.subject.trim(),
      description: values.description.trim(),
      requesterId: values.requesterId,
      productId: values.productId || null,
      ...splitLinked(values.linked),
      categoryId: values.categoryId || null,
      priority: values.priority,
      channel: values.channel,
      ...(ticket ? { version: ticket.version } : { assigneeId: values.assigneeId || null }),
    }
    try {
      const saved = ticket
        ? await api.put<TicketView>(`/helpdesk/tickets/${ticket.id}`, body)
        : await api.post<TicketView>('/helpdesk/tickets', body)
      queryClient.setQueryData(['helpdesk', 'ticket', saved.id], saved)
      await invalidateHelpDesk(queryClient)
      toast.success(ticket ? 'Changes saved.' : `${saved.number} created.`)
      onSaved(saved)
    } catch (error) {
      // a conflict means the cached ticket is stale: reload it so the next save carries the current version
      if (ticket && error instanceof ApiError && error.status === 409)
        void queryClient.invalidateQueries({ queryKey: ['helpdesk', 'ticket', ticket.id] })
      const linkedError =
        error instanceof ApiError
          ? error.problem.errors?.find((e) => e.field === 'linkedType' || e.field === 'linkedId')
          : undefined
      if (linkedError) form.setError('linked', { type: 'server', message: linkedError.message })
      const applied = applyFieldErrors(error, form.setError, FIELDS)
      if (!applied && !linkedError) setFormError(problemMessage(error))
    }
  })

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>{ticket ? `Edit ${ticket.number}` : 'New ticket'}</DialogTitle>
          <DialogDescription>
            Who is asking, about what, and anything it relates to — so whoever picks it up has the
            context.
          </DialogDescription>
        </DialogHeader>
        <form noValidate onSubmit={(e) => void onSubmit(e)} className="space-y-4">
          <TextField form={form} name="subject" label="Subject" maxLength={200} />
          <TextAreaField
            form={form}
            name="description"
            label="Description"
            rows={4}
            maxLength={10000}
          />
          <PartyPicker
            id="field-requesterId"
            label="Requester"
            value={form.watch('requesterId')}
            current={ticket?.requester ?? requester}
            error={errors.requesterId?.message}
            noneLabel="Choose…"
            onChange={(id) => form.setValue('requesterId', id, revalidate)}
          />
          <CatalogProductPicker
            id="field-productId"
            label="Product"
            value={form.watch('productId')}
            current={ticket?.product}
            error={errors.productId?.message}
            onChange={(id) => form.setValue('productId', id, revalidate)}
          />
          <LinkedRecordPicker
            id="field-linked"
            value={form.watch('linked')}
            current={ticket?.linked}
            error={errors.linked?.message}
            onChange={(value) => form.setValue('linked', value, revalidate)}
          />
          <div className="grid gap-3 sm:grid-cols-3">
            <CategorySelect
              id="field-categoryId"
              label="Category"
              value={form.watch('categoryId')}
              current={ticket?.category}
              error={errors.categoryId?.message}
              onChange={(id) => form.setValue('categoryId', id, revalidate)}
            />
            <Field id="field-priority" label="Priority" error={errors.priority?.message}>
              <NativeSelect id="field-priority" {...form.register('priority')}>
                {PRIORITIES.map((p: TicketPriority) => (
                  <option key={p} value={p}>
                    {PRIORITY_LABELS[p]}
                  </option>
                ))}
              </NativeSelect>
            </Field>
            <Field id="field-channel" label="Channel" error={errors.channel?.message}>
              <NativeSelect id="field-channel" {...form.register('channel')}>
                {(Object.keys(CHANNEL_LABELS) as TicketChannel[]).map((c) => (
                  <option key={c} value={c}>
                    {CHANNEL_LABELS[c]}
                  </option>
                ))}
              </NativeSelect>
            </Field>
          </div>
          {!ticket && (
            <AgentSelect
              id="field-assigneeId"
              label="Assignee"
              blankLabel="Category default"
              value={form.watch('assigneeId')}
              error={errors.assigneeId?.message}
              onChange={(id) => form.setValue('assigneeId', id, revalidate)}
            />
          )}
          <FormError message={formError} />
          <DialogFooter>
            <Button type="button" variant="ghost" onClick={onClose}>
              Cancel
            </Button>
            <Button type="submit" disabled={form.formState.isSubmitting}>
              {ticket ? 'Save changes' : 'Create ticket'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
