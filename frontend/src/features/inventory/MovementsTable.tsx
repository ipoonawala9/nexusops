import { Link } from 'react-router'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { subjectPath } from '@/features/records/SubjectLink'
import type { MovementView } from '@/lib/api/types'
import { formatDateTime } from '@/lib/format'
import { MOVEMENT_KIND_LABELS } from './labels'
import { formatQuantity } from './quantity'

/** The ledger, newest first. Order movements link to their order. */
export function MovementsTable({
  movements,
  showProduct = false,
}: {
  movements: MovementView[]
  showProduct?: boolean
}) {
  return (
    <div className="overflow-x-auto rounded-lg border">
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>When</TableHead>
            {showProduct && <TableHead>Product</TableHead>}
            <TableHead>Warehouse</TableHead>
            <TableHead>Movement</TableHead>
            <TableHead className="text-right">Change</TableHead>
            <TableHead className="text-right">On hand after</TableHead>
            <TableHead>Reason</TableHead>
            <TableHead>By</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {movements.map((m) => {
            const orderPath = subjectPath(m.referenceType, m.referenceId)
            return (
              <TableRow key={m.id}>
                <TableCell className="whitespace-nowrap">{formatDateTime(m.occurredAt)}</TableCell>
                {showProduct && (
                  <TableCell>
                    <Link
                      to={`/app/products/${m.product.id}`}
                      className="underline-offset-4 hover:underline"
                    >
                      {m.product.sku}
                    </Link>{' '}
                    {m.product.name}
                  </TableCell>
                )}
                <TableCell>{m.warehouse.code}</TableCell>
                <TableCell>{MOVEMENT_KIND_LABELS[m.kind]}</TableCell>
                <TableCell className="text-right tabular-nums">
                  {m.quantity > 0 ? '+' : ''}
                  {formatQuantity(m.quantity)}
                </TableCell>
                <TableCell className="text-right tabular-nums">
                  {formatQuantity(m.onHandAfter)}
                </TableCell>
                <TableCell>
                  {orderPath && m.reason ? (
                    <Link to={orderPath} className="underline-offset-4 hover:underline">
                      {m.reason}
                    </Link>
                  ) : (
                    (m.reason ?? '—')
                  )}
                </TableCell>
                <TableCell>{m.actor?.name ?? '—'}</TableCell>
              </TableRow>
            )
          })}
        </TableBody>
      </Table>
    </div>
  )
}
