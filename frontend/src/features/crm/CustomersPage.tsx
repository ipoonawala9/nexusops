import { keepPreviousData, useQuery } from '@tanstack/react-query'
import type { FormEvent } from 'react'
import { Link, useSearchParams } from 'react-router'
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
import { Pagination } from '@/components/Pagination'
import { EmptyState, ErrorState, ListSkeleton, PageHeader } from '@/components/states'
import { useApi } from '@/lib/api/ApiContext'
import type { CustomerRow, Page } from '@/lib/api/types'
import { toQuery } from '@/lib/query'
import { formatTotals } from './money'

export function CustomersPage() {
  const api = useApi()
  const [params, setParams] = useSearchParams()
  const q = params.get('q') ?? ''
  const page = Math.max(0, Number(params.get('page') ?? '0') || 0)
  const customers = useQuery({
    queryKey: ['crm-customers', { q, page }],
    queryFn: () => api.get<Page<CustomerRow>>(`/crm/customers?${toQuery({ q, page, size: 20 })}`),
    placeholderData: keepPreviousData,
    refetchOnMount: 'always',
  })

  function onSearch(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const value = new FormData(event.currentTarget).get('q')
    const text = typeof value === 'string' ? value.trim() : ''
    setParams(text ? { q: text } : {}, { replace: true })
  }

  return (
    <>
      <PageHeader
        title="Customers"
        description="People and organizations with an active customer role."
      />
      <form onSubmit={onSearch} className="mb-4 flex items-end gap-2" role="search">
        <div className="space-y-1.5">
          <Label htmlFor="customers-q">Search customers</Label>
          <Input id="customers-q" name="q" defaultValue={q} />
        </div>
        <Button type="submit" variant="outline">
          Search
        </Button>
      </form>
      {customers.isPending ? (
        <ListSkeleton />
      ) : customers.isError ? (
        <ErrorState error={customers.error} onRetry={() => void customers.refetch()} />
      ) : customers.data.items.length === 0 ? (
        <EmptyState
          title={q ? 'No customers match.' : 'No customers yet.'}
          description="Converting a lead or winning a deal makes the account a customer."
        />
      ) : (
        <>
          <div className="overflow-x-auto rounded-lg border">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Name</TableHead>
                  <TableHead>Open deals</TableHead>
                  <TableHead>Open value</TableHead>
                  <TableHead>Won</TableHead>
                  <TableHead>Won value</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {customers.data.items.map((row) => (
                  <TableRow key={row.party.id}>
                    <TableCell>
                      <Link
                        to={`/app/crm/customers/${row.party.id}`}
                        className="font-medium underline-offset-4 hover:underline"
                      >
                        {row.party.name}
                      </Link>
                    </TableCell>
                    <TableCell>{row.openCount}</TableCell>
                    <TableCell>{formatTotals(row.openValue)}</TableCell>
                    <TableCell>{row.wonCount}</TableCell>
                    <TableCell>{formatTotals(row.wonValue)}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
          <Pagination
            page={customers.data.page}
            size={customers.data.size}
            total={customers.data.total}
            onPage={(p) => setParams({ ...(q ? { q } : {}), page: String(p) }, { replace: true })}
          />
        </>
      )}
    </>
  )
}
