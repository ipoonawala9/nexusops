import { Link } from 'react-router'
import { EmptyState, PageHeader } from '@/components/states'
import { buttonVariants } from '@/components/ui/button'
import { PERMISSIONS, useCan } from '@/features/auth/permissions'
import { useTenantSession } from '@/features/auth/tenantSession'

export function ComingSoonPage({
  title,
  phase,
  description,
  module,
}: {
  title: string
  phase: number
  description: string
  module?: string
}) {
  const session = useTenantSession()
  const can = useCan()
  const modules = session.state.status === 'authenticated' ? session.state.profile.modules : []

  if (module && !modules.includes(module)) {
    return (
      <>
        <PageHeader title={title} />
        <EmptyState
          title={`${title} is not enabled for this workspace.`}
          description="An owner or admin can enable it under Settings → Modules."
          action={
            can(PERMISSIONS.settingsRead) ? (
              <Link to="/app/settings/modules" className={buttonVariants({ variant: 'outline' })}>
                Manage modules
              </Link>
            ) : undefined
          }
        />
      </>
    )
  }
  return (
    <>
      <PageHeader title={title} description={description} />
      <EmptyState
        title="Coming soon"
        description={`${title} is delivered in Phase ${phase} of the NexusOps roadmap.`}
      />
    </>
  )
}
