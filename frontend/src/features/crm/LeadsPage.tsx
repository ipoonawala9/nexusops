import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
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
import { Pagination } from '@/components/Pagination'
import { EmptyState, ErrorState, ListSkeleton, PageHeader } from '@/components/states'
import { PERMISSIONS, useCan } from '@/features/auth/permissions'
import { useApi } from '@/lib/api/ApiContext'
import type { LeadView, Page } from '@/lib/api/types'
import { formatMoney } from '@/lib/format'
import { toQuery } from '@/lib/query'
import { LEAD_SOURCE_LABELS, LEAD_STATUS_LABELS } from './labels'
import { LeadFormDialog } from './LeadFormDialog'
import { LeadImportDialog } from './LeadImportDialog'

const SIZE = 20

export function LeadsPage() {
  const api = useApi()
  const can = useCan()
  const navigate = useNavigate()
  const [params, setParams] = useSearchParams()
  const q = params.get('q') ?? ''
  const status = params.get('status') ?? ''
  const owner = params.get('owner') ?? ''
  const source = params.get('source') ?? ''
  const page = Math.max(0, Number(params.get('page') ?? '0') || 0)
  const [creating, setCreating] = useState(false)
  const [importing, setImporting] = useState(false)
  const canManage = can(PERMISSIONS.leadManage)

  const leads = useQuery({
    queryKey: ['leads', { q, status, owner, source, page }],
    queryFn: () =>
      api.get<Page<LeadView>>(`/leads?${toQuery({ q, status, owner, source, page, size: SIZE })}`),
    placeholderData: keepPreviousData,
    refetchOnMount: 'always',
  })

  function update(next: Record<string, string>) {
    const merged = new URLSearchParams(params)
    for (const [key, value] of Object.entries(next)) {
      if (value) merged.set(key, value)
      else merged.delete(key)
    }
    setParams(merged, { replace: true })
  }

  function onSearch(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const value = new FormData(event.currentTarget).get('q')
    update({ q: typeof value === 'string' ? value.trim() : '', page: '' })
  }

  return (
    <>
      <PageHeader
        title="Leads"
        description="Prospects to contact, qualify and convert into customers."
        actions={
          canManage && (
            <>
              <Button variant="outline" onClick={() => setImporting(true)}>
                Import CSV
              </Button>
              <Button onClick={() => setCreating(true)}>New lead</Button>
            </>
          )
        }
      />
      <div className="mb-4 flex flex-wrap items-end gap-3">
        <form onSubmit={onSearch} className="flex items-end gap-2" role="search">
          <div className="space-y-1.5">
            <Label htmlFor="leads-q">Search leads</Label>
            <Input id="leads-q" name="q" defaultValue={q} placeholder="Name, company or email" />
          </div>
          <Button type="submit" variant="outline">
            Search
          </Button>
        </form>
        <div className="space-y-1.5">
          <Label htmlFor="leads-status">Status</Label>
          <NativeSelect
            id="leads-status"
            value={status}
            onChange={(e) => update({ status: e.target.value, page: '' })}
          >
            <option value="">Open</option>
            {Object.entries(LEAD_STATUS_LABELS).map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </NativeSelect>
        </div>
        <div className="space-y-1.5">
          <Label htmlFor="leads-owner">Owner</Label>
          <NativeSelect
            id="leads-owner"
            value={owner}
            onChange={(e) => update({ owner: e.target.value, page: '' })}
          >
            <option value="">Anyone</option>
            <option value="me">Mine</option>
            <option value="unassigned">Unassigned</option>
          </NativeSelect>
        </div>
        <div className="space-y-1.5">
          <Label htmlFor="leads-source">Source</Label>
          <NativeSelect
            id="leads-source"
            value={source}
            onChange={(e) => update({ source: e.target.value, page: '' })}
          >
            <option value="">Any source</option>
            {Object.entries(LEAD_SOURCE_LABELS).map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </NativeSelect>
        </div>
      </div>

      {leads.isPending ? (
        <ListSkeleton />
      ) : leads.isError ? (
        <ErrorState error={leads.error} onRetry={() => void leads.refetch()} />
      ) : leads.data.items.length === 0 ? (
        <EmptyState
          title={
            q || status || owner || source ? 'No leads match these filters.' : 'No open leads.'
          }
          description={
            canManage
              ? 'Add a lead, or import the spreadsheet you already keep.'
              : 'Leads will show up here once someone adds them.'
          }
          action={
            page > 0 && (
              <Button variant="outline" onClick={() => update({ page: '' })}>
                Back to first page
              </Button>
            )
          }
        />
      ) : (
        <>
          <div className="overflow-x-auto rounded-lg border">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Name</TableHead>
                  <TableHead>Company</TableHead>
                  <TableHead>Status</TableHead>
                  <TableHead>Source</TableHead>
                  <TableHead>Owner</TableHead>
                  <TableHead>Estimated value</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {leads.data.items.map((lead) => (
                  <TableRow key={lead.id}>
                    <TableCell>
                      <Link
                        to={`/app/crm/leads/${lead.id}`}
                        className="font-medium underline-offset-4 hover:underline"
                      >
                        {lead.name}
                      </Link>
                    </TableCell>
                    <TableCell>{lead.companyName ?? '—'}</TableCell>
                    <TableCell>
                      <Badge variant={lead.status === 'DISQUALIFIED' ? 'outline' : 'secondary'}>
                        {LEAD_STATUS_LABELS[lead.status]}
                      </Badge>
                    </TableCell>
                    <TableCell>{LEAD_SOURCE_LABELS[lead.source]}</TableCell>
                    <TableCell>{lead.owner?.name ?? 'Unassigned'}</TableCell>
                    <TableCell>
                      {lead.estimatedValue != null && lead.currency
                        ? formatMoney(lead.estimatedValue, lead.currency)
                        : '—'}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
          <Pagination
            page={leads.data.page}
            size={leads.data.size}
            total={leads.data.total}
            onPage={(p) => update({ page: String(p) })}
          />
        </>
      )}

      {creating && (
        <LeadFormDialog
          onClose={() => setCreating(false)}
          onSaved={(saved) => {
            setCreating(false)
            navigate(`/app/crm/leads/${saved.id}`)
          }}
        />
      )}
      {importing && <LeadImportDialog onClose={() => setImporting(false)} />}
    </>
  )
}
