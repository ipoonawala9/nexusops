import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { Checkbox } from '@/components/form/Checkbox'
import { EmptyState, ErrorState, ListSkeleton, PageHeader } from '@/components/states'
import { PERMISSIONS, useCan } from '@/features/auth/permissions'
import { useTenantSession } from '@/features/auth/tenantSession'
import { MODULE_PHASES } from '@/features/shell/nav'
import { useApi } from '@/lib/api/ApiContext'
import { problemMessage } from '@/lib/api/problems'
import type { ModuleState } from '@/lib/api/types'

export function ModulesSettingsPage() {
  const api = useApi()
  const queryClient = useQueryClient()
  const session = useTenantSession()
  const canManage = useCan()(PERMISSIONS.modulesManage)
  const plan = session.state.status === 'authenticated' ? session.state.profile.tenant.planCode : ''
  const modules = useQuery({
    queryKey: ['tenant-modules'],
    queryFn: () => api.get<ModuleState[]>('/tenant/modules'),
  })
  const toggle = useMutation({
    mutationFn: ({ code, enabled }: { code: string; enabled: boolean }) =>
      api.put<ModuleState>(`/tenant/modules/${encodeURIComponent(code)}`, { enabled }),
    onSuccess: (updated) => {
      queryClient.setQueryData<ModuleState[]>(['tenant-modules'], (old) =>
        old?.map((m) => (m.code === updated.code ? updated : m)),
      )
      toast.success(`${updated.name} ${updated.enabled ? 'enabled' : 'disabled'}.`)
      void session.reloadProfile() // the navigation shows enabled modules only
    },
    onError: (error) => toast.error(problemMessage(error)),
  })

  return (
    <>
      <PageHeader
        title="Modules"
        description={`Turn business modules on or off. Your ${plan} plan limits how many can be on at once.`}
      />
      {modules.isPending ? (
        <ListSkeleton rows={4} />
      ) : modules.isError ? (
        <ErrorState error={modules.error} onRetry={() => void modules.refetch()} />
      ) : modules.data.length === 0 ? (
        <EmptyState
          title="No modules available"
          description="Modules will appear here as they ship."
        />
      ) : (
        <ul className="divide-y rounded-lg border">
          {modules.data.map((module) => {
            const id = `module-${module.code}`
            return (
              <li key={module.code} className="flex items-center justify-between gap-4 p-4">
                <div>
                  <label htmlFor={id} className="font-medium">
                    {module.name}
                  </label>
                  <p id={`${id}-desc`} className="text-sm text-muted-foreground">
                    {MODULE_PHASES[module.code]?.description ?? ''}
                  </p>
                </div>
                <Checkbox
                  id={id}
                  role="switch"
                  aria-describedby={`${id}-desc`}
                  checked={module.enabled}
                  disabled={!canManage || toggle.isPending}
                  onChange={(event) =>
                    toggle.mutate({ code: module.code, enabled: event.target.checked })
                  }
                />
              </li>
            )
          })}
        </ul>
      )}
    </>
  )
}
