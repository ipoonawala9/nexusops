import { useQuery } from '@tanstack/react-query'
import { Fragment } from 'react'
import { Link, useSearchParams } from 'react-router'
import { Label } from '@/components/ui/label'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { NativeSelect } from '@/components/form/NativeSelect'
import { ErrorState, ListSkeleton, PageHeader } from '@/components/states'
import { useApi } from '@/lib/api/ApiContext'
import type { DashboardView } from '@/lib/api/types'
import { formatDate, formatMoney } from '@/lib/format'
import { toQuery } from '@/lib/query'
import { LEAD_STATUS_LABELS, OPEN_LEAD_STATUSES } from './labels'
import { formatTotals } from './money'

export function CrmDashboardPage() {
  const api = useApi()
  const [params, setParams] = useSearchParams()
  const owner = params.get('owner') ?? ''
  const dashboard = useQuery({
    queryKey: ['crm-dashboard', owner],
    queryFn: () => api.get<DashboardView>(`/crm/dashboard?${toQuery({ owner })}`),
    refetchOnMount: 'always',
  })

  return (
    <>
      <PageHeader
        title="Sales dashboard"
        description="Where leads and deals stand. Months follow the workspace time zone."
      />
      <div className="mb-4 space-y-1.5">
        <Label htmlFor="dashboard-owner">Show</Label>
        <NativeSelect
          id="dashboard-owner"
          value={owner}
          onChange={(e) =>
            setParams(e.target.value ? { owner: e.target.value } : {}, { replace: true })
          }
        >
          <option value="">Everyone</option>
          <option value="me">Only mine</option>
        </NativeSelect>
      </div>
      {dashboard.isPending ? (
        <ListSkeleton />
      ) : dashboard.isError ? (
        <ErrorState error={dashboard.error} onRetry={() => void dashboard.refetch()} />
      ) : (
        <div className="grid gap-4 lg:grid-cols-2">
          {dashboard.data.leads && (
            <section aria-label="Leads" className="space-y-3 rounded-lg border p-4">
              <h2 className="font-semibold">Leads</h2>
              <dl className="grid grid-cols-[1fr_auto] gap-y-1 text-sm">
                {OPEN_LEAD_STATUSES.map((status) => (
                  <Fragment key={status}>
                    <dt>{LEAD_STATUS_LABELS[status]}</dt>
                    <dd className="text-right font-medium">
                      {dashboard.data.leads?.open[status] ?? 0}
                    </dd>
                  </Fragment>
                ))}
                <dt>New in the last 30 days</dt>
                <dd className="text-right font-medium">{dashboard.data.leads.newLast30Days}</dd>
                <dt>Conversion rate (90 days)</dt>
                <dd className="text-right font-medium">
                  {dashboard.data.leads.conversionRate == null
                    ? '—'
                    : `${Math.round(dashboard.data.leads.conversionRate * 100)}%`}
                </dd>
              </dl>
              <Link to="/app/crm/leads" className="text-sm underline">
                Open leads
              </Link>
            </section>
          )}
          {dashboard.data.pipeline && (
            <>
              <section aria-label="Pipeline" className="space-y-3 rounded-lg border p-4">
                <h2 className="font-semibold">Pipeline</h2>
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead>Stage</TableHead>
                      <TableHead>Deals</TableHead>
                      <TableHead>Value</TableHead>
                      <TableHead>Weighted</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {dashboard.data.pipeline.stages.map((s) => (
                      <TableRow key={s.stage.id}>
                        <TableCell>{s.stage.name}</TableCell>
                        <TableCell>{s.count}</TableCell>
                        <TableCell>{formatTotals(s.totals)}</TableCell>
                        <TableCell>{formatTotals(s.weighted)}</TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </section>
              <section aria-label="Won this month" className="rounded-lg border p-4 text-sm">
                <h2 className="font-semibold">Won this month</h2>
                <p className="text-2xl font-semibold">
                  {dashboard.data.pipeline.wonThisMonth.count}
                </p>
                <p>{formatTotals(dashboard.data.pipeline.wonThisMonth.totals)}</p>
                <p className="mt-2 text-muted-foreground">
                  Lost this month: {dashboard.data.pipeline.lostThisMonth.count}
                </p>
              </section>
              <section
                aria-label="Closing soon"
                className="space-y-2 rounded-lg border p-4 text-sm"
              >
                <h2 className="font-semibold">Closing in the next 30 days</h2>
                {dashboard.data.pipeline.closingSoon.length === 0 ? (
                  <p className="text-muted-foreground">Nothing due.</p>
                ) : (
                  <ul className="space-y-1">
                    {dashboard.data.pipeline.closingSoon.map((o) => (
                      <li key={o.id}>
                        <Link
                          to={`/app/crm/opportunities/${o.id}`}
                          className="underline-offset-4 hover:underline"
                        >
                          {o.name}
                        </Link>{' '}
                        <span className="text-muted-foreground">
                          {[
                            o.account?.name,
                            formatDate(o.expectedCloseOn),
                            o.amount != null && o.currency
                              ? formatMoney(o.amount, o.currency)
                              : null,
                          ]
                            .filter(Boolean)
                            .join(' · ')}
                        </span>
                      </li>
                    ))}
                  </ul>
                )}
              </section>
            </>
          )}
        </div>
      )}
    </>
  )
}
