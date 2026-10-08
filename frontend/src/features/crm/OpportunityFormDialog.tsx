import { zodResolver } from '@hookform/resolvers/zod'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { describedBy, Field } from '@/components/form/Field'
import { FormError } from '@/components/form/FormError'
import { NativeSelect } from '@/components/form/NativeSelect'
import { TextAreaField } from '@/components/form/TextAreaField'
import { TextField } from '@/components/form/TextField'
import { useApi } from '@/lib/api/ApiContext'
import { applyFieldErrors, problemMessage } from '@/lib/api/problems'
import type { OpportunityView, PartyRef, StageView } from '@/lib/api/types'
import { invalidateCrmFigures } from './invalidation'
import { OwnerSelect } from './OwnerSelect'
import { PartyPicker } from './PartyPicker'
import { opportunitySchema, type OpportunityValues } from './schemas'

const FIELDS = [
  'name',
  'accountId',
  'contactId',
  'stageId',
  'amount',
  'currency',
  'expectedCloseOn',
  'ownerId',
  'description',
] as const

/** Create (in an open stage) or edit an opportunity. Stage moves use MoveStageControl. */
export function OpportunityFormDialog({
  opportunity,
  account,
  onClose,
  onSaved,
}: {
  opportunity?: OpportunityView
  account?: PartyRef
  onClose: () => void
  onSaved: (saved: OpportunityView) => void
}) {
  const api = useApi()
  const queryClient = useQueryClient()
  const [formError, setFormError] = useState<string | null>(null)
  const stages = useQuery({
    queryKey: ['crm-stages'],
    queryFn: () => api.get<StageView[]>('/crm/pipeline/stages'),
  })
  const openStages = (stages.data ?? []).filter((s) => s.kind === 'OPEN')
  const form = useForm<OpportunityValues>({
    resolver: zodResolver(opportunitySchema),
    defaultValues: {
      name: opportunity?.name ?? '',
      accountId: opportunity?.account?.id ?? account?.id ?? '',
      contactId: opportunity?.contact?.id ?? '',
      stageId: '',
      amount: opportunity?.amount == null ? '' : String(opportunity.amount),
      currency: opportunity?.currency ?? '',
      expectedCloseOn: opportunity?.expectedCloseOn ?? '',
      ownerId: opportunity?.owner?.id ?? '',
      description: opportunity?.description ?? '',
    },
  })
  const errors = form.formState.errors
  const closeError = errors.expectedCloseOn?.message

  const submit = form.handleSubmit(async (values) => {
    setFormError(null)
    const body = {
      name: values.name,
      accountId: values.accountId,
      contactId: values.contactId || null,
      stageId: opportunity ? null : values.stageId || openStages[0]?.id || null,
      amount: values.amount === '' ? null : Number(values.amount),
      currency: values.currency === '' ? null : values.currency.toUpperCase(),
      expectedCloseOn: values.expectedCloseOn || null,
      ownerId: values.ownerId || null,
      description: values.description.trim() || null,
      ...(opportunity ? { version: opportunity.version } : {}),
    }
    try {
      const saved = opportunity
        ? await api.put<OpportunityView>(`/opportunities/${opportunity.id}`, body)
        : await api.post<OpportunityView>('/opportunities', body)
      queryClient.setQueryData(['opportunity', saved.id], saved)
      await queryClient.invalidateQueries({ queryKey: ['crm-board'] })
      await queryClient.invalidateQueries({ queryKey: ['opportunities'] })
      await invalidateCrmFigures(queryClient)
      toast.success(opportunity ? 'Changes saved.' : `${saved.name} created.`)
      onSaved(saved)
    } catch (error) {
      if (!applyFieldErrors(error, form.setError, FIELDS)) setFormError(problemMessage(error))
    }
  })

  return (
    <Dialog
      open
      onOpenChange={(open) => {
        if (!open) onClose()
      }}
    >
      <DialogContent className="max-h-[90vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle>{opportunity ? `Edit ${opportunity.name}` : 'New opportunity'}</DialogTitle>
          <DialogDescription>
            A deal with a person or organization in your directory.
          </DialogDescription>
        </DialogHeader>
        <form noValidate onSubmit={submit} className="space-y-4">
          <TextField form={form} name="name" label="Name" maxLength={200} />
          <PartyPicker
            id="field-accountId"
            label="Account"
            value={form.watch('accountId')}
            current={opportunity?.account ?? account}
            error={errors.accountId?.message}
            noneLabel="Choose…"
            onChange={(id) =>
              form.setValue('accountId', id, { shouldValidate: form.formState.isSubmitted })
            }
          />
          <PartyPicker
            id="field-contactId"
            label="Contact"
            kind="PERSON"
            value={form.watch('contactId')}
            current={opportunity?.contact}
            error={errors.contactId?.message}
            onChange={(id) => form.setValue('contactId', id)}
          />
          {!opportunity && (
            <Field id="field-stageId" label="Stage" error={errors.stageId?.message}>
              <NativeSelect id="field-stageId" {...form.register('stageId')}>
                {openStages.map((s) => (
                  <option key={s.id} value={s.id}>
                    {s.name}
                  </option>
                ))}
              </NativeSelect>
            </Field>
          )}
          <div className="grid gap-3 sm:grid-cols-3">
            <TextField form={form} name="amount" label="Amount" />
            <TextField
              form={form}
              name="currency"
              label="Currency"
              maxLength={3}
              hint="Defaults to the workspace currency"
            />
            <Field id="field-expectedCloseOn" label="Expected close" error={closeError}>
              <Input
                id="field-expectedCloseOn"
                type="date"
                aria-invalid={closeError ? true : undefined}
                aria-describedby={describedBy('field-expectedCloseOn', closeError)}
                {...form.register('expectedCloseOn')}
              />
            </Field>
          </div>
          <OwnerSelect
            value={form.watch('ownerId')}
            onChange={(id) => form.setValue('ownerId', id)}
            current={opportunity?.owner}
            blankLabel={opportunity ? 'Unassigned' : 'Me (default)'}
            error={errors.ownerId?.message}
          />
          <TextAreaField form={form} name="description" label="Description" maxLength={5000} />
          <FormError message={formError} />
          <DialogFooter>
            <Button type="button" variant="ghost" onClick={onClose}>
              Cancel
            </Button>
            <Button type="submit" disabled={form.formState.isSubmitting}>
              {opportunity ? 'Save changes' : 'Create opportunity'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
