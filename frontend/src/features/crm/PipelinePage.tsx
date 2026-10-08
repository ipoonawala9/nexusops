import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
import { NativeSelect } from '@/components/form/NativeSelect'
import { ErrorState, ListSkeleton, PageHeader } from '@/components/states'
import { PERMISSIONS, useCan } from '@/features/auth/permissions'
import { useApi } from '@/lib/api/ApiContext'
import type { BoardView } from '@/lib/api/types'
import { formatDate, formatMoney } from '@/lib/format'
import { toQuery } from '@/lib/query'
import { formatTotals } from './money'
import { MoveStageControl } from './MoveStageControl'
import { OpportunityFormDialog } from './OpportunityFormDialog'

/** The pipeline board (D16): a column per stage; Won and Lost show the last 30 days. Cards move with a menu. */
export function PipelinePage() {
  const api = useApi()
  const can = useCan()
  const navigate = useNavigate()
  const [params, setParams] = useSearchParams()
  const owner = params.get('owner') ?? ''
  const [creating, setCreating] = useState(false)
  const canManage = can(PERMISSIONS.opportunityManage)
  const board = useQuery({
    queryKey: ['crm-board', owner],
    queryFn: () => api.get<BoardView>(`/crm/pipeline/board?${toQuery({ owner })}`),
    refetchOnMount: 'always',
  })
  const stages = board.data?.columns.map((c) => c.stage) ?? []

  return (
    <>
      <PageHeader
        title="Pipeline"
        description="Open deals by stage. Totals are per currency."
        actions={canManage && <Button onClick={() => setCreating(true)}>New opportunity</Button>}
      />
      <div className="mb-4 space-y-1.5">
        <Label htmlFor="board-owner">Show</Label>
        <NativeSelect
          id="board-owner"
          value={owner}
          onChange={(e) =>
            setParams(e.target.value ? { owner: e.target.value } : {}, { replace: true })
          }
        >
          <option value="">Everyone's deals</option>
          <option value="me">My deals</option>
        </NativeSelect>
      </div>
      {board.isPending ? (
        <ListSkeleton />
      ) : board.isError ? (
        <ErrorState error={board.error} onRetry={() => void board.refetch()} />
      ) : (
        <div className="flex gap-3 overflow-x-auto pb-4">
          {board.data.columns.map((column) => (
            <section
              key={column.stage.id}
              aria-label={column.stage.name}
              className="w-72 shrink-0 space-y-2 rounded-lg border bg-muted/20 p-3"
            >
              <header className="space-y-0.5">
                <h2 className="font-semibold">{column.stage.name}</h2>
                <p className="text-xs text-muted-foreground">
                  <span>{column.count === 1 ? '1 deal' : `${column.count} deals`}</span>
                  {column.stage.kind === 'OPEN' ? ` · ${column.stage.probability}%` : ''}
                </p>
                <p className="text-sm">{formatTotals(column.totals)}</p>
                {column.stage.kind === 'OPEN' && column.weighted.length > 0 && (
                  <p className="text-xs text-muted-foreground">
                    Weighted {formatTotals(column.weighted)}
                  </p>
                )}
                {column.stage.kind !== 'OPEN' && (
                  <p className="text-xs text-muted-foreground">Closed in the last 30 days</p>
                )}
              </header>
              <ul className="space-y-2">
                {column.opportunities.map((o) => (
                  <li key={o.id} className="space-y-1 rounded-md border bg-background p-2 text-sm">
                    <Link
                      to={`/app/crm/opportunities/${o.id}`}
                      className="font-medium underline-offset-4 hover:underline"
                    >
                      {o.name}
                    </Link>
                    <p className="text-xs text-muted-foreground">
                      {o.account?.name ?? 'Restricted record'}
                    </p>
                    <p className="text-xs">
                      {o.amount != null && o.currency
                        ? formatMoney(o.amount, o.currency)
                        : 'No amount'}
                      {o.expectedCloseOn ? ` · closes ${formatDate(o.expectedCloseOn)}` : ''}
                    </p>
                    {canManage && <MoveStageControl opportunity={o} stages={stages} />}
                  </li>
                ))}
              </ul>
              {column.count > column.opportunities.length && (
                <p className="text-xs text-muted-foreground">
                  Showing {column.opportunities.length} of {column.count}.
                </p>
              )}
            </section>
          ))}
        </div>
      )}
      {creating && (
        <OpportunityFormDialog
          onClose={() => setCreating(false)}
          onSaved={(saved) => {
            setCreating(false)
            navigate(`/app/crm/opportunities/${saved.id}`)
          }}
        />
      )}
    </>
  )
}
