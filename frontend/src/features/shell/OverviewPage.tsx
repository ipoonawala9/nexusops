import { Link } from 'react-router'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { PageHeader } from '@/components/states'
import { PERMISSIONS, useCan } from '@/features/auth/permissions'
import { useTenantSession } from '@/features/auth/tenantSession'
import { MODULE_PHASES } from './nav'

/** A placeholder dashboard (blueprint §23): real widgets arrive with the modules and Insights (Phase 11). */
export function OverviewPage() {
  const session = useTenantSession()
  const can = useCan()
  if (session.state.status !== 'authenticated') return null
  const { profile } = session.state
  const enabled = profile.modules.map((code) => MODULE_PHASES[code]?.label ?? code)

  return (
    <>
      <PageHeader
        title={`Welcome, ${profile.user.firstName}`}
        description={`${profile.tenant.name} · ${profile.tenant.planCode} plan`}
      />
      <div className="grid gap-4 md:grid-cols-2">
        <Card>
          <CardHeader>
            <CardTitle>Modules</CardTitle>
          </CardHeader>
          <CardContent className="text-sm">
            {enabled.length ? enabled.join(', ') : 'No modules enabled yet.'}{' '}
            {can(PERMISSIONS.settingsRead) && (
              <Link to="/app/settings/modules" className="underline">
                Manage modules
              </Link>
            )}
          </CardContent>
        </Card>
        <Card>
          <CardHeader>
            <CardTitle>Get started</CardTitle>
          </CardHeader>
          <CardContent className="space-y-1 text-sm">
            {can(PERMISSIONS.userRead) && (
              <p>
                <Link to="/app/settings/users" className="underline">
                  Invite your team
                </Link>
              </p>
            )}
            {can(PERMISSIONS.roleRead) && (
              <p>
                <Link to="/app/settings/roles" className="underline">
                  Design roles and permissions
                </Link>
              </p>
            )}
            {can(PERMISSIONS.auditRead) && (
              <p>
                <Link to="/app/audit" className="underline">
                  Review the audit log
                </Link>
              </p>
            )}
            <p className="text-muted-foreground">Dashboards arrive with Insights (Phase 11).</p>
          </CardContent>
        </Card>
      </div>
    </>
  )
}
