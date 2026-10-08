import { zodResolver } from '@hookform/resolvers/zod'
import { useQueryClient } from '@tanstack/react-query'
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
import { Field } from '@/components/form/Field'
import { FormError } from '@/components/form/FormError'
import { NativeSelect } from '@/components/form/NativeSelect'
import { TextAreaField } from '@/components/form/TextAreaField'
import { TextField } from '@/components/form/TextField'
import { useApi } from '@/lib/api/ApiContext'
import { applyFieldErrors, problemMessage } from '@/lib/api/problems'
import type { LeadView } from '@/lib/api/types'
import { LEAD_SOURCE_LABELS } from './labels'
import { OwnerSelect } from './OwnerSelect'
import { leadSchema, type LeadValues } from './schemas'

const FIELDS = [
  'firstName',
  'lastName',
  'companyName',
  'jobTitle',
  'email',
  'phone',
  'source',
  'ownerId',
  'estimatedValue',
  'currency',
  'description',
] as const

/** Create or edit a lead. On create a blank owner means "me" (the server's rule); on edit it means unassigned. */
export function LeadFormDialog({
  lead,
  onClose,
  onSaved,
}: {
  lead?: LeadView
  onClose: () => void
  onSaved: (saved: LeadView) => void
}) {
  const api = useApi()
  const queryClient = useQueryClient()
  const [formError, setFormError] = useState<string | null>(null)
  const form = useForm<LeadValues>({
    resolver: zodResolver(leadSchema),
    defaultValues: {
      firstName: lead?.firstName ?? '',
      lastName: lead?.lastName ?? '',
      companyName: lead?.companyName ?? '',
      jobTitle: lead?.jobTitle ?? '',
      email: lead?.email ?? '',
      phone: lead?.phone ?? '',
      source: lead?.source ?? 'OTHER',
      ownerId: lead?.owner?.id ?? '',
      estimatedValue: lead?.estimatedValue == null ? '' : String(lead.estimatedValue),
      currency: lead?.currency ?? '',
      description: lead?.description ?? '',
    },
  })
  const blank = (v: string) => v.trim() || null

  const submit = form.handleSubmit(async (values) => {
    setFormError(null)
    const body = {
      firstName: blank(values.firstName),
      lastName: blank(values.lastName),
      companyName: blank(values.companyName),
      jobTitle: blank(values.jobTitle),
      email: blank(values.email),
      phone: blank(values.phone),
      source: values.source,
      ownerId: values.ownerId || null,
      estimatedValue: values.estimatedValue === '' ? null : Number(values.estimatedValue),
      currency: values.currency === '' ? null : values.currency.toUpperCase(),
      description: blank(values.description),
      ...(lead ? { version: lead.version } : {}),
    }
    try {
      const saved = lead
        ? await api.put<LeadView>(`/leads/${lead.id}`, body)
        : await api.post<LeadView>('/leads', body)
      queryClient.setQueryData(['lead', saved.id], saved)
      await queryClient.invalidateQueries({ queryKey: ['leads'] })
      toast.success(lead ? 'Changes saved.' : `${saved.name} added.`)
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
          <DialogTitle>{lead ? `Edit ${lead.name}` : 'New lead'}</DialogTitle>
          <DialogDescription>
            A prospect, as you heard about them. Converting it later creates the customer record.
          </DialogDescription>
        </DialogHeader>
        <form noValidate onSubmit={submit} className="space-y-4">
          <div className="grid gap-3 sm:grid-cols-2">
            <TextField form={form} name="firstName" label="First name" maxLength={80} />
            <TextField form={form} name="lastName" label="Last name" maxLength={80} />
          </div>
          <div className="grid gap-3 sm:grid-cols-2">
            <TextField form={form} name="companyName" label="Company" maxLength={200} />
            <TextField form={form} name="jobTitle" label="Job title" maxLength={100} />
          </div>
          <div className="grid gap-3 sm:grid-cols-2">
            <TextField form={form} name="email" label="Email" type="email" />
            <TextField form={form} name="phone" label="Phone" maxLength={40} />
          </div>
          <div className="grid gap-3 sm:grid-cols-3">
            <Field id="field-source" label="Source">
              <NativeSelect id="field-source" {...form.register('source')}>
                {Object.entries(LEAD_SOURCE_LABELS).map(([value, label]) => (
                  <option key={value} value={value}>
                    {label}
                  </option>
                ))}
              </NativeSelect>
            </Field>
            <TextField form={form} name="estimatedValue" label="Estimated value" />
            <TextField
              form={form}
              name="currency"
              label="Currency"
              maxLength={3}
              hint="Defaults to the workspace currency"
            />
          </div>
          <OwnerSelect
            value={form.watch('ownerId')}
            onChange={(id) => form.setValue('ownerId', id)}
            current={lead?.owner}
            error={form.formState.errors.ownerId?.message}
          />
          <TextAreaField form={form} name="description" label="Notes" maxLength={5000} />
          <FormError message={formError} />
          <DialogFooter>
            <Button type="button" variant="ghost" onClick={onClose}>
              Cancel
            </Button>
            <Button type="submit" disabled={form.formState.isSubmitting}>
              {lead ? 'Save changes' : 'Create lead'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
