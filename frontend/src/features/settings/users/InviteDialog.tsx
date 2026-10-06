import { zodResolver } from '@hookform/resolvers/zod'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
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
import { describedBy, Field } from '@/components/form/Field'
import { FormError } from '@/components/form/FormError'
import { NativeSelect } from '@/components/form/NativeSelect'
import { TextField } from '@/components/form/TextField'
import { ListSkeleton } from '@/components/states'
import { emailField, MESSAGES } from '@/features/auth/schemas'
import { PERMISSIONS, useCan } from '@/features/auth/permissions'
import { useApi } from '@/lib/api/ApiContext'
import { applyFieldErrors, problemMessage } from '@/lib/api/problems'
import type { InvitationView, RoleView } from '@/lib/api/types'

const schema = z.object({ email: emailField, roleId: z.string().min(1, MESSAGES.required) })
type Values = z.infer<typeof schema>

export function InviteDialog({ onClose }: { onClose: () => void }) {
  const api = useApi()
  const canReadRoles = useCan()(PERMISSIONS.roleRead)
  const queryClient = useQueryClient()
  const roles = useQuery({
    queryKey: ['roles'],
    refetchOnMount: 'always',
    queryFn: () => api.get<RoleView[]>('/roles'),
    enabled: canReadRoles,
  })
  const [formError, setFormError] = useState<string | null>(null)
  const form = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: { email: '', roleId: '' },
  })
  const roleError = form.formState.errors.roleId?.message
  // Custom roles first: they are what most invitations should use.
  const options = roles.data
    ? [...roles.data].sort((a, b) => Number(a.system) - Number(b.system))
    : []
  const firstOption = options[0]?.id
  useEffect(() => {
    if (firstOption && !form.getValues('roleId')) form.setValue('roleId', firstOption)
  }, [firstOption, form])

  const submit = form.handleSubmit(async (values) => {
    setFormError(null)
    try {
      const invitation = await api.post<InvitationView>('/invitations', values)
      toast.success(`Invitation sent to ${invitation.email}.`)
      await queryClient.invalidateQueries({ queryKey: ['invitations'] })
      onClose()
    } catch (error) {
      if (!applyFieldErrors(error, form.setError, ['email', 'roleId'] as const))
        setFormError(problemMessage(error))
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
          <DialogTitle>Invite people</DialogTitle>
          <DialogDescription>
            They'll get an email with a link that's valid for 7 days.
          </DialogDescription>
        </DialogHeader>
        {!canReadRoles ? (
          <p className="text-sm">You need permission to view roles before you can invite people.</p>
        ) : roles.isPending ? (
          <ListSkeleton rows={2} />
        ) : (
          <form noValidate onSubmit={submit} className="space-y-4">
            <TextField form={form} name="email" label="Email" type="email" autoComplete="off" />
            <Field id="field-roleId" label="Role" error={roleError}>
              <NativeSelect
                id="field-roleId"
                aria-invalid={roleError ? true : undefined}
                aria-describedby={describedBy('field-roleId', roleError)}
                {...form.register('roleId')}
              >
                {options.map((role) => (
                  <option key={role.id} value={role.id}>
                    {role.name}
                  </option>
                ))}
              </NativeSelect>
            </Field>
            <FormError
              message={formError ?? (roles.isError ? problemMessage(roles.error) : null)}
            />
            <DialogFooter>
              <Button type="button" variant="ghost" onClick={onClose}>
                Cancel
              </Button>
              <Button type="submit" disabled={form.formState.isSubmitting}>
                Send invitation
              </Button>
            </DialogFooter>
          </form>
        )}
      </DialogContent>
    </Dialog>
  )
}
