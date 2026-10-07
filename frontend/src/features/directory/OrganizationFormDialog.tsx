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
import { FormError } from '@/components/form/FormError'
import { TextField } from '@/components/form/TextField'
import { DuplicateNotice } from '@/features/records/DuplicateNotice'
import { useDuplicateGuard } from '@/features/records/duplicates'
import { useApi } from '@/lib/api/ApiContext'
import { applyFieldErrors, problemMessage } from '@/lib/api/problems'
import type { PartyView } from '@/lib/api/types'
import { blankToNull, organizationSchema, type OrganizationValues } from './schemas'

const FIELDS = ['name', 'domain', 'website', 'email', 'phone'] as const

/** Creates an organization, or edits {@code organization} (sending the version it was loaded with). */
export function OrganizationFormDialog({
  organization,
  onClose,
  onSaved,
}: {
  organization?: PartyView
  onClose: () => void
  onSaved: (saved: PartyView) => void
}) {
  const api = useApi()
  const queryClient = useQueryClient()
  const guard = useDuplicateGuard()
  const [formError, setFormError] = useState<string | null>(null)
  const form = useForm<OrganizationValues>({
    resolver: zodResolver(organizationSchema),
    defaultValues: {
      name: organization?.name ?? '',
      domain: organization?.domain ?? '',
      website: organization?.website ?? '',
      email: organization?.email ?? '',
      phone: organization?.phone ?? '',
    },
  })

  const submit = form.handleSubmit(async (values) => {
    setFormError(null)
    const duplicateReason = guard.reasonToSend()
    if (duplicateReason === false) return
    const body = {
      ...blankToNull(values),
      duplicateReason,
      ...(organization ? { version: organization.version } : {}),
    }
    try {
      const saved = organization
        ? await api.put<PartyView>(`/organizations/${organization.id}`, body)
        : await api.post<PartyView>('/organizations', body)
      queryClient.setQueryData(['party', saved.id], saved)
      await queryClient.invalidateQueries({ queryKey: ['parties'] })
      toast.success(organization ? 'Changes saved.' : `${saved.name} added.`)
      onSaved(saved)
    } catch (error) {
      if (guard.handleError(error)) return
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
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{organization ? `Edit ${organization.name}` : 'New organization'}</DialogTitle>
          <DialogDescription>
            A company is one record, whether it buys from you, sells to you, or both.
          </DialogDescription>
        </DialogHeader>
        <form noValidate onSubmit={submit} className="space-y-4">
          <TextField form={form} name="name" label="Name" maxLength={200} />
          <div className="grid gap-3 sm:grid-cols-2">
            <TextField form={form} name="domain" label="Domain" hint="e.g. example.com" />
            <TextField form={form} name="website" label="Website" />
          </div>
          <div className="grid gap-3 sm:grid-cols-2">
            <TextField form={form} name="email" label="Email" type="email" autoComplete="off" />
            <TextField form={form} name="phone" label="Phone" maxLength={40} />
          </div>
          {guard.candidates && (
            <DuplicateNotice
              candidates={guard.candidates}
              reason={guard.reason}
              onReason={guard.setReason}
              reasonError={guard.reasonError}
              onOpen={onClose}
            />
          )}
          <FormError message={formError} />
          <DialogFooter>
            <Button type="button" variant="ghost" onClick={onClose}>
              Cancel
            </Button>
            <Button type="submit" disabled={form.formState.isSubmitting}>
              {guard.candidates
                ? organization
                  ? 'Save anyway'
                  : 'Create anyway'
                : organization
                  ? 'Save changes'
                  : 'Create organization'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
