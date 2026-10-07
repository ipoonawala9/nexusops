import { zodResolver } from '@hookform/resolvers/zod'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { describedBy, Field } from '@/components/form/Field'
import { FormError } from '@/components/form/FormError'
import { NativeSelect } from '@/components/form/NativeSelect'
import { TextField } from '@/components/form/TextField'
import { DuplicateNotice } from '@/features/records/DuplicateNotice'
import { useDuplicateGuard } from '@/features/records/duplicates'
import { useApi } from '@/lib/api/ApiContext'
import { applyFieldErrors, problemMessage } from '@/lib/api/problems'
import type { Page, PartySummary, PartyView } from '@/lib/api/types'
import { toQuery } from '@/lib/query'
import { blankToNull, personSchema, type PersonValues } from './schemas'

const FIELDS = ['firstName', 'lastName', 'jobTitle', 'organizationId', 'email', 'phone'] as const
const IDENTITY = ['firstName', 'lastName', 'email', 'organizationId'] as const

/** Creates a person, or edits {@code person} (sending the version it was loaded with). */
export function PersonFormDialog({
  person,
  onClose,
  onSaved,
}: {
  person?: PartyView
  onClose: () => void
  onSaved: (saved: PartyView) => void
}) {
  const api = useApi()
  const queryClient = useQueryClient()
  const [formError, setFormError] = useState<string | null>(null)
  const [search, setSearch] = useState('')
  const [picked, setPicked] = useState<{ id: string; name: string } | null>(null)
  const organizations = useQuery({
    queryKey: ['parties', 'organization-options', search.trim()],
    queryFn: () =>
      api.get<Page<PartySummary>>(
        `/parties?${toQuery({ kind: 'ORGANIZATION', q: search.trim(), size: 20 })}`,
      ),
  })
  const form = useForm<PersonValues>({
    resolver: zodResolver(personSchema),
    defaultValues: {
      firstName: person?.firstName ?? '',
      lastName: person?.lastName ?? '',
      jobTitle: person?.jobTitle ?? '',
      organizationId: person?.organization?.id ?? '',
      email: person?.email ?? '',
      phone: person?.phone ?? '',
    },
  })
  const guard = useDuplicateGuard(form.watch, IDENTITY)
  const organizationId = form.watch('organizationId')
  const options = organizations.data?.items ?? []
  // The current and the picked organization stay listed whatever the search returns, so the select never loses its value.
  const pinned = [person?.organization, picked].filter(
    (organization, index, all): organization is { id: string; name: string } =>
      !!organization && all.findIndex((other) => other?.id === organization.id) === index,
  )
  const organizationError = form.formState.errors.organizationId?.message

  const submit = form.handleSubmit(async (values) => {
    setFormError(null)
    const duplicateReason = guard.reasonToSend()
    if (duplicateReason === false) return
    const body = {
      ...blankToNull(values),
      duplicateReason,
      ...(person ? { version: person.version } : {}),
    }
    try {
      const saved = person
        ? await api.put<PartyView>(`/persons/${person.id}`, body)
        : await api.post<PartyView>('/persons', body)
      queryClient.setQueryData(['party', saved.id], saved)
      await queryClient.invalidateQueries({ queryKey: ['parties'] })
      toast.success(person ? 'Changes saved.' : `${saved.name} added.`)
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
          <DialogTitle>{person ? `Edit ${person.name}` : 'New person'}</DialogTitle>
          <DialogDescription>
            One record per real person. If they already exist, open their record instead.
          </DialogDescription>
        </DialogHeader>
        <form noValidate onSubmit={submit} className="space-y-4">
          <div className="grid gap-3 sm:grid-cols-2">
            <TextField form={form} name="firstName" label="First name" maxLength={80} />
            <TextField form={form} name="lastName" label="Last name" maxLength={80} />
          </div>
          <TextField form={form} name="jobTitle" label="Job title" maxLength={100} />
          <Field id="field-organizationSearch" label="Find an organization">
            <Input
              id="field-organizationSearch"
              type="search"
              autoComplete="off"
              value={search}
              onChange={(event) => setSearch(event.target.value)}
            />
          </Field>
          <Field id="field-organizationId" label="Organization" error={organizationError}>
            <NativeSelect
              id="field-organizationId"
              value={organizationId}
              aria-invalid={organizationError ? true : undefined}
              aria-describedby={describedBy('field-organizationId', organizationError)}
              {...form.register('organizationId', {
                onChange: (event: { target: { value: string } }) =>
                  setPicked(
                    options.find((organization) => organization.id === event.target.value) ??
                      pinned.find((organization) => organization.id === event.target.value) ??
                      null,
                  ),
              })}
            >
              <option value="">No organization</option>
              {pinned.map((organization) => (
                <option key={organization.id} value={organization.id}>
                  {organization.name}
                </option>
              ))}
              {options
                .filter((organization) => !pinned.some((other) => other.id === organization.id))
                .map((organization) => (
                  <option key={organization.id} value={organization.id}>
                    {organization.name}
                  </option>
                ))}
            </NativeSelect>
          </Field>
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
                ? person
                  ? 'Save anyway'
                  : 'Create anyway'
                : person
                  ? 'Save changes'
                  : 'Create person'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
