import { zodResolver } from '@hookform/resolvers/zod'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState, type ReactNode } from 'react'
import { useForm } from 'react-hook-form'
import { Link, useNavigate, useParams } from 'react-router'
import { toast } from 'sonner'
import { z } from 'zod'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { ConfirmDialog } from '@/components/ConfirmDialog'
import { FormError } from '@/components/form/FormError'
import { TextField } from '@/components/form/TextField'
import { ErrorState, ListSkeleton, PageHeader } from '@/components/states'
import { requiredText } from '@/features/auth/schemas'
import { PERMISSIONS, useCan } from '@/features/auth/permissions'
import { useTenantSession } from '@/features/auth/tenantSession'
import { useApi } from '@/lib/api/ApiContext'
import { applyFieldErrors, problemMessage } from '@/lib/api/problems'
import type { PermissionView, RoleView } from '@/lib/api/types'
import { PermissionMatrix } from './PermissionMatrix'

export function RoleDetailPage() {
  const { roleId = '' } = useParams()
  const api = useApi()
  const role = useQuery({
    queryKey: ['role', roleId],
    queryFn: () => api.get<RoleView>(`/roles/${roleId}`),
  })
  const catalog = useQuery({
    queryKey: ['permissions'],
    queryFn: () => api.get<PermissionView[]>('/permissions'),
  })

  const back = (
    <Link to="/app/settings/roles" className="text-sm underline-offset-4 hover:underline">
      ← All roles
    </Link>
  )
  if (role.isPending || catalog.isPending)
    return (
      <>
        {back}
        <ListSkeleton />
      </>
    )
  if (role.isError)
    return (
      <>
        {back}
        <ErrorState error={role.error} onRetry={() => void role.refetch()} />
      </>
    )
  if (catalog.isError)
    return (
      <>
        {back}
        <ErrorState error={catalog.error} onRetry={() => void catalog.refetch()} />
      </>
    )
  return <RoleEditor key={role.data.id} role={role.data} catalog={catalog.data} back={back} />
}

const detailsSchema = z.object({
  name: requiredText(60),
  description: z.string().trim().max(255, 'Use at most 255 characters.'), // server: RoleDtos @Size(max = 255), AuthorizationService
})
type DetailsValues = z.infer<typeof detailsSchema>

function RoleEditor({
  role,
  catalog,
  back,
}: {
  role: RoleView
  catalog: PermissionView[]
  back: ReactNode
}) {
  const api = useApi()
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const session = useTenantSession()
  const canManage = useCan()(PERMISSIONS.roleManage) && !role.system
  const [selected, setSelected] = useState<Set<string>>(() => new Set(role.permissions))
  const [saving, setSaving] = useState(false)
  const [permissionsError, setPermissionsError] = useState<string | null>(null)
  const [detailsError, setDetailsError] = useState<string | null>(null)
  const [deleting, setDeleting] = useState(false)
  const [deleteBusy, setDeleteBusy] = useState(false)
  const [deleteError, setDeleteError] = useState<string | null>(null)
  const form = useForm<DetailsValues>({
    resolver: zodResolver(detailsSchema),
    defaultValues: { name: role.name, description: role.description ?? '' },
  })

  function stored(updated: RoleView) {
    queryClient.setQueryData(['role', updated.id], updated)
    void queryClient.invalidateQueries({ queryKey: ['roles'] })
  }

  const saveDetails = form.handleSubmit(async (values) => {
    setDetailsError(null)
    try {
      stored(await api.patch<RoleView>(`/roles/${role.id}`, values))
      toast.success('Role updated.')
    } catch (error) {
      if (!applyFieldErrors(error, form.setError, ['name', 'description'] as const))
        setDetailsError(problemMessage(error))
    }
  })

  async function savePermissions() {
    setSaving(true)
    setPermissionsError(null)
    try {
      stored(
        await api.put<RoleView>(`/roles/${role.id}/permissions`, {
          permissions: [...selected].sort(),
        }),
      )
      toast.success('Permissions saved.')
      await session.reloadProfile() // the signed-in user may hold this role
    } catch (error) {
      setPermissionsError(problemMessage(error))
    } finally {
      setSaving(false)
    }
  }

  async function remove() {
    setDeleteBusy(true)
    setDeleteError(null)
    try {
      await api.del(`/roles/${role.id}`)
      await queryClient.invalidateQueries({ queryKey: ['roles'] })
      toast.success('Role deleted.')
      setDeleting(false)
      navigate('/app/settings/roles', { replace: true })
    } catch (error) {
      setDeleteError(problemMessage(error))
    } finally {
      setDeleteBusy(false)
    }
  }

  return (
    <div className="space-y-6">
      {back}
      <PageHeader
        title={role.name}
        description={role.description ?? undefined}
        actions={
          <>
            <Badge variant={role.system ? 'secondary' : 'outline'}>
              {role.system ? 'System' : 'Custom'}
            </Badge>
            {canManage && (
              <Button
                variant="destructive"
                size="sm"
                onClick={() => {
                  setDeleteError(null)
                  setDeleting(true)
                }}
              >
                Delete role
              </Button>
            )}
          </>
        }
      />
      {role.system && (
        <p className="text-sm text-muted-foreground">System roles can't be changed.</p>
      )}
      {canManage && (
        <form noValidate onSubmit={saveDetails} className="max-w-xl space-y-3">
          <TextField form={form} name="name" label="Name" />
          <TextField form={form} name="description" label="Description" />
          <FormError message={detailsError} />
          <Button type="submit" size="sm" disabled={form.formState.isSubmitting}>
            Save details
          </Button>
        </form>
      )}
      <section aria-labelledby="permissions-heading" className="space-y-3">
        <h2 id="permissions-heading" className="text-lg font-semibold">
          Permissions
        </h2>
        <PermissionMatrix
          catalog={catalog}
          selected={selected}
          disabled={!canManage || saving}
          onToggle={(code, on) =>
            setSelected((current) => {
              const next = new Set(current)
              if (on) next.add(code)
              else next.delete(code)
              return next
            })
          }
        />
        <FormError message={permissionsError} />
        {canManage && (
          <Button onClick={() => void savePermissions()} disabled={saving}>
            Save permissions
          </Button>
        )}
      </section>
      <ConfirmDialog
        open={deleting}
        title={`Delete ${role.name}?`}
        description="People must not hold this role. Deleting it can't be undone."
        confirmLabel="Delete"
        onConfirm={() => void remove()}
        onCancel={() => setDeleting(false)}
        busy={deleteBusy}
        error={deleteError}
      />
    </div>
  )
}
