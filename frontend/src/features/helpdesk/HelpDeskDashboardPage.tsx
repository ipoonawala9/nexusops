import { useQuery } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { Link } from 'react-router'
import { ErrorState, ListSkeleton, PageHeader } from '@/components/states'
import { useApi } from '@/lib/api/ApiContext'
import type { HelpDeskDashboard } from '@/lib/api/types'
import { OPEN_STATUSES, PRIORITIES, PRIORITY_LABELS, TICKET_STATUS_LABELS } from './labels'
import { formatMinutes, formatRate } from './sla'

function Stat({ label, value, to }: { label: string; value: ReactNode; to?: string }) {
  return (
    <div className="col-span-2 grid grid-cols-subgrid">
      <dt className="text-muted-foreground">
        {to ? (
          <Link to={to} className="underline-offset-4 hover:underline">
            {label}
          </Link>
        ) : (
          label
        )}
      </dt>
      <dd className="text-right font-medium tabular-nums">{value}</dd>
    </div>
  )
}

function minutes(value: number | null | undefined): string {
  return value == null ? '—' : formatMinutes(value)
}

/** D15: open work now, and how the last 30 days went. */
export function HelpDeskDashboardPage() {
  const api = useApi()
  const dashboard = useQuery({
    queryKey: ['helpdesk', 'dashboard'],
    queryFn: () => api.get<HelpDeskDashboard>('/helpdesk/dashboard'),
    refetchOnMount: 'always',
  })
  return (
    <>
      <PageHeader
        title="HelpDesk dashboard"
        description="Open tickets now, and how the last 30 days went against your SLA targets."
      />
      {dashboard.isPending ? (
        <ListSkeleton />
      ) : dashboard.isError ? (
        <ErrorState error={dashboard.error} onRetry={() => void dashboard.refetch()} />
      ) : (
        <div className="grid gap-4 lg:grid-cols-3">
          <section aria-label="Open tickets" className="space-y-3 rounded-lg border p-4">
            <h2 className="font-semibold">Open tickets</h2>
            <dl className="grid grid-cols-[1fr_auto] gap-x-3 gap-y-1 text-sm">
              {OPEN_STATUSES.map((s) => (
                <Stat
                  key={s}
                  label={TICKET_STATUS_LABELS[s]}
                  value={dashboard.data.openByStatus[s] ?? 0}
                />
              ))}
              <Stat
                label="Unassigned"
                value={dashboard.data.unassigned}
                to="/app/helpdesk/tickets?assignee=unassigned"
              />
              <Stat
                label="Breached"
                value={dashboard.data.breached}
                to="/app/helpdesk/tickets?sla=breached"
              />
              <Stat
                label="At risk"
                value={dashboard.data.atRisk}
                to="/app/helpdesk/tickets?sla=at_risk"
              />
            </dl>
          </section>
          <section aria-label="Open by priority" className="space-y-3 rounded-lg border p-4">
            <h2 className="font-semibold">Open by priority</h2>
            <dl className="grid grid-cols-[1fr_auto] gap-x-3 gap-y-1 text-sm">
              {PRIORITIES.map((p) => (
                <Stat
                  key={p}
                  label={PRIORITY_LABELS[p]}
                  value={dashboard.data.openByPriority[p] ?? 0}
                />
              ))}
            </dl>
          </section>
          <section aria-label="Last 30 days" className="space-y-3 rounded-lg border p-4">
            <h2 className="font-semibold">Last 30 days</h2>
            <dl className="grid grid-cols-[1fr_auto] gap-x-3 gap-y-1 text-sm">
              <Stat label="Created" value={dashboard.data.last30Days.created} />
              <Stat label="Resolved" value={dashboard.data.last30Days.resolved} />
              <Stat
                label="Average first response"
                value={minutes(dashboard.data.last30Days.averageFirstResponseMinutes)}
              />
              <Stat
                label="Median first response"
                value={minutes(dashboard.data.last30Days.medianFirstResponseMinutes)}
              />
              <Stat
                label="Average resolution"
                value={minutes(dashboard.data.last30Days.averageResolutionMinutes)}
              />
              <Stat
                label="First response within SLA"
                value={formatRate(dashboard.data.last30Days.firstResponseMetRate)}
              />
              <Stat
                label="Resolved within SLA"
                value={formatRate(dashboard.data.last30Days.resolutionMetRate)}
              />
              <Stat label="Reopened" value={formatRate(dashboard.data.last30Days.reopenRate)} />
            </dl>
          </section>
        </div>
      )}
    </>
  )
}
