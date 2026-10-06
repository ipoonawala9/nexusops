import { zodResolver } from '@hookform/resolvers/zod'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { toast } from 'sonner'
import { z } from 'zod'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Separator } from '@/components/ui/separator'
import { Checkbox } from '@/components/form/Checkbox'
import { FormError } from '@/components/form/FormError'
import { TextField } from '@/components/form/TextField'
import { ListSkeleton } from '@/components/states'
import { StatusBadge } from '@/components/StatusBadge'
import { requiredText } from '@/features/auth/schemas'
import { PERMISSIONS, useCan } from '@/features/auth/permissions'
import { useTenantSession } from '@/features/auth/tenantSession'
import { useApi } from '@/lib/api/ApiContext'
import { applyFieldErrors, problemMessage } from '@/lib/api/problems'
import type { RoleView, UserView } from '@/lib/api/types'
import { fullName } from '@/lib/format'
import { quietly } from '@/lib/quietly'

/** Manage one person. Each section is shown only with its permission; the server re-checks every call. */
export function UserDialog({ person, onClose }: { person: UserView; onClose: () => void }) {
  const can = useCan()
  const session = useTenantSession()
  const queryClient = useQueryClient()
  const [current, setCurrent] = useState(person)
  const isSelf =
    session.state.status === 'authenticated' && session.state.profile.user.id === person.id

  async function changed(updated: UserView, message: string) {
    setCurrent(updated)
    toast.success(message)
    // The change succeeded: a failing refresh must not show up as a failed change.
    await quietly(() => queryClient.invalidateQueries({ queryKey: ['users'] }))
    if (isSelf) await quietly(() => session.reloadProfile())
  }

  return (
    <Dialog
      open
      onOpenChange={(open) => {
        if (!open) onClose()
      }}
    >
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>{fullName(current)}</DialogTitle>
          <DialogDescription>
            {current.email} · <StatusBadge status={current.status} />
          </DialogDescription>
        </DialogHeader>
        {can(PERMISSIONS.userUpdate) && <NameSection person={current} onChanged={changed} />}
        {can(PERMISSIONS.userDisable) && !isSelf && current.status !== 'INVITED' && (
          <>
            <Separator />
            <StatusSection person={current} onChanged={changed} />
          </>
        )}
        {can(PERMISSIONS.roleAssign) && (
          <>
            <Separator />
            <RolesSection person={current} onChanged={changed} />
          </>
        )}
      </DialogContent>
    </Dialog>
  )
}

type Changed = (updated: UserView, message: string) => Promise<void>

const nameSchema = z.object({ firstName: requiredText(80), lastName: requiredText(80) })
type NameValues = z.infer<typeof nameSchema>

function NameSection({ person, onChanged }: { person: UserView; onChanged: Changed }) {
  const api = useApi()
  const [error, setError] = useState<string | null>(null)
  const form = useForm<NameValues>({
    resolver: zodResolver(nameSchema),
    defaultValues: { firstName: person.firstName, lastName: person.lastName },
  })
  const submit = form.handleSubmit(async (values) => {
    setError(null)
    try {
      await onChanged(await api.patch<UserView>(`/users/${person.id}`, values), 'Name updated.')
    } catch (e) {
      if (!applyFieldErrors(e, form.setError, ['firstName', 'lastName'] as const))
        setError(problemMessage(e))
    }
  })
  return (
    <form noValidate onSubmit={submit} className="space-y-3">
      <h2 className="text-sm font-semibold">Name</h2>
      <div className="grid gap-3 sm:grid-cols-2">
        <TextField form={form} name="firstName" label="First name" />
        <TextField form={form} name="lastName" label="Last name" />
      </div>
      <FormError message={error} />
      <Button type="submit" size="sm" disabled={form.formState.isSubmitting}>
        Save name
      </Button>
    </form>
  )
}

function StatusSection({ person, onChanged }: { person: UserView; onChanged: Changed }) {
  const api = useApi()
  const [confirming, setConfirming] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const disabling = person.status === 'ACTIVE'

  async function apply() {
    setBusy(true)
    setError(null)
    try {
      const updated = await api.patch<UserView>(`/users/${person.id}`, {
        status: disabling ? 'DISABLED' : 'ACTIVE',
      })
      setConfirming(false)
      await onChanged(updated, disabling ? 'User disabled.' : 'User enabled.')
    } catch (e) {
      setError(problemMessage(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <section className="space-y-3">
      <h2 className="text-sm font-semibold">Access</h2>
      {!confirming ? (
        <Button
          variant={disabling ? 'destructive' : 'outline'}
          size="sm"
          onClick={() => (disabling ? setConfirming(true) : void apply())}
          disabled={busy}
        >
          {disabling ? 'Disable user' : 'Enable user'}
        </Button>
      ) : (
        <div className="space-y-2">
          <p className="text-sm">
            {fullName(person)} will be signed out immediately and can't sign in until enabled again.
          </p>
          <div className="flex gap-2">
            <Button variant="destructive" size="sm" onClick={() => void apply()} disabled={busy}>
              Confirm disable
            </Button>
            <Button variant="ghost" size="sm" onClick={() => setConfirming(false)}>
              Cancel
            </Button>
          </div>
        </div>
      )}
      <FormError message={error} />
    </section>
  )
}

function RolesSection({ person, onChanged }: { person: UserView; onChanged: Changed }) {
  const api = useApi()
  const can = useCan()
  const canReadRoles = can(PERMISSIONS.roleRead)
  const roles = useQuery({
    queryKey: ['roles'],
    refetchOnMount: 'always',
    queryFn: () => api.get<RoleView[]>('/roles'),
    enabled: canReadRoles,
  })
  const [chosen, setChosen] = useState<string[]>(() => person.roles.map((r) => r.id))
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function save() {
    setBusy(true)
    setError(null)
    try {
      await onChanged(
        await api.put<UserView>(`/users/${person.id}/roles`, { roleIds: chosen }),
        'Roles updated.',
      )
    } catch (e) {
      setError(problemMessage(e))
    } finally {
      setBusy(false)
    }
  }

  if (!canReadRoles) {
    return (
      <p className="text-sm text-muted-foreground">
        You need permission to view roles before you can change them.
      </p>
    )
  }
  return (
    <section className="space-y-3">
      <h2 className="text-sm font-semibold">Roles</h2>
      {roles.isPending ? (
        <ListSkeleton rows={3} />
      ) : roles.isError ? (
        <FormError message={problemMessage(roles.error)} />
      ) : (
        <fieldset className="space-y-2">
          <legend className="sr-only">Roles</legend>
          {roles.data.map((role) => (
            <label key={role.id} className="flex items-center gap-2 text-sm">
              <Checkbox
                checked={chosen.includes(role.id)}
                onChange={(e) =>
                  setChosen((ids) =>
                    e.target.checked ? [...ids, role.id] : ids.filter((id) => id !== role.id),
                  )
                }
              />
              {role.name}
            </label>
          ))}
        </fieldset>
      )}
      <FormError message={error} />
      <Button size="sm" onClick={() => void save()} disabled={busy || roles.isPending}>
        Save roles
      </Button>
    </section>
  )
}
