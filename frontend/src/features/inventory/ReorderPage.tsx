import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router'
import { toast } from 'sonner'
import { ConfirmDialog } from '@/components/ConfirmDialog'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { FormError } from '@/components/form/FormError'
import { EmptyState, ErrorState, ListSkeleton, PageHeader } from '@/components/states'
import { PERMISSIONS, useCan } from '@/features/auth/permissions'
import { useApi } from '@/lib/api/ApiContext'
import { problemMessage } from '@/lib/api/problems'
import type { PurchaseOrderView, ReorderRuleView, ReorderSuggestion } from '@/lib/api/types'
import { invalidateInventory } from './invalidation'
import { formatQuantity, positiveQuantitySchema } from './quantity'
import { ReorderRuleDialog } from './ReorderRuleDialog'

const keyOf = (s: { product: { id: string }; warehouse: { id: string } }) =>
  `${s.product.id}:${s.warehouse.id}`
const nameOf = (s: { product: { sku: string }; warehouse: { code: string } }) =>
  `${s.product.sku} at ${s.warehouse.code}`

export function ReorderPage() {
  const api = useApi()
  const can = useCan()
  const queryClient = useQueryClient()
  const canRules = can(PERMISSIONS.reorderManage)
  const canDraft = canRules && can(PERMISSIONS.purchaseManage)
  const suggestions = useQuery({
    queryKey: ['inventory', 'suggestions'],
    queryFn: () => api.get<ReorderSuggestion[]>('/inventory/reorder-suggestions'),
    refetchOnMount: 'always',
  })
  const rules = useQuery({
    queryKey: ['inventory', 'reorder-rules'],
    queryFn: () => api.get<ReorderRuleView[]>('/inventory/reorder-rules'),
  })
  const [selected, setSelected] = useState<Record<string, boolean>>({})
  const [quantities, setQuantities] = useState<Record<string, string>>({})
  const [created, setCreated] = useState<PurchaseOrderView[]>([])
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [editing, setEditing] = useState<ReorderRuleView | 'new' | null>(null)
  const [deleting, setDeleting] = useState<ReorderRuleView | null>(null)
  const [deleteError, setDeleteError] = useState<string | null>(null)

  const quantityFor = (s: ReorderSuggestion) => quantities[keyOf(s)] ?? String(s.suggestedQuantity)

  async function draft() {
    const chosen = (suggestions.data ?? []).filter((s) => selected[keyOf(s)])
    if (chosen.length === 0) {
      setError('Select at least one suggestion.')
      return
    }
    const bad = chosen.find((s) => !positiveQuantitySchema.safeParse(quantityFor(s)).success)
    if (bad) {
      setError(`Enter a quantity greater than 0 for ${nameOf(bad)}.`)
      return
    }
    setBusy(true)
    setError(null)
    try {
      const result = await api.post<{ orders: PurchaseOrderView[] }>(
        '/inventory/reorder-suggestions/purchase-orders',
        {
          items: chosen.map((s) => ({
            productId: s.product.id,
            warehouseId: s.warehouse.id,
            quantity: Number(quantityFor(s)),
          })),
        },
      )
      setCreated(result.orders)
      setSelected({})
      setQuantities({})
      await invalidateInventory(queryClient)
      toast.success(
        result.orders.length === 1
          ? 'Draft purchase order created.'
          : `${result.orders.length} draft purchase orders created.`,
      )
    } catch (e) {
      setError(problemMessage(e))
    } finally {
      setBusy(false)
    }
  }

  async function remove(rule: ReorderRuleView) {
    setBusy(true)
    setDeleteError(null)
    try {
      await api.del(`/inventory/reorder-rules/${rule.id}`)
      await invalidateInventory(queryClient)
      toast.success('Reorder rule deleted.')
      setDeleting(null)
    } catch (e) {
      setDeleteError(problemMessage(e))
      // the rule may be gone or changed meanwhile: show the current list behind the dialog
      void queryClient.invalidateQueries({ queryKey: ['inventory', 'reorder-rules'] })
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="space-y-6">
      <PageHeader
        title="Reorder"
        description="Products below their minimum, with the figures behind each suggestion."
      />
      <Card>
        <CardHeader className="flex flex-row items-center justify-between gap-2">
          <CardTitle>Suggestions</CardTitle>
          {canDraft && (suggestions.data?.length ?? 0) > 0 && (
            <Button disabled={busy} onClick={() => void draft()}>
              Create purchase orders
            </Button>
          )}
        </CardHeader>
        <CardContent className="space-y-3">
          <FormError message={error} />
          {created.length > 0 && (
            <p role="status" className="text-sm">
              Drafts to review and place:{' '}
              {created.map((o, i) => (
                <span key={o.id}>
                  {i > 0 && ', '}
                  <Link
                    to={`/app/inventory/purchase-orders/${o.id}`}
                    className="underline-offset-4 hover:underline"
                  >
                    {o.number}
                  </Link>
                </span>
              ))}
            </p>
          )}
          {suggestions.isPending ? (
            <ListSkeleton rows={3} />
          ) : suggestions.isError ? (
            <ErrorState error={suggestions.error} onRetry={() => void suggestions.refetch()} />
          ) : suggestions.data.length === 0 ? (
            <EmptyState
              title="Nothing to reorder."
              description="Every product with a reorder rule is at or above its minimum."
            />
          ) : (
            <div className="overflow-x-auto rounded-lg border">
              <Table>
                <TableHeader>
                  <TableRow>
                    {canDraft && <TableHead className="w-8" />}
                    <TableHead>Product</TableHead>
                    <TableHead>Warehouse</TableHead>
                    <TableHead>Why</TableHead>
                    <TableHead>Supplier</TableHead>
                    <TableHead className="w-32">Order</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {suggestions.data.map((s) => (
                    <TableRow key={keyOf(s)}>
                      {canDraft && (
                        <TableCell>
                          <input
                            type="checkbox"
                            aria-label={`Select ${nameOf(s)}`}
                            disabled={!s.supplier}
                            checked={!!selected[keyOf(s)]}
                            onChange={(e) =>
                              setSelected({ ...selected, [keyOf(s)]: e.target.checked })
                            }
                          />
                        </TableCell>
                      )}
                      <TableCell>
                        <Link
                          to={`/app/products/${s.product.id}`}
                          className="underline-offset-4 hover:underline"
                        >
                          {s.product.sku}
                        </Link>{' '}
                        {s.product.name}
                      </TableCell>
                      <TableCell>{s.warehouse.code}</TableCell>
                      <TableCell className="max-w-md min-w-64 text-sm whitespace-normal">
                        {s.explanation}
                      </TableCell>
                      <TableCell>
                        {s.supplier?.name ?? (
                          <span className="text-muted-foreground">No supplier</span>
                        )}
                      </TableCell>
                      <TableCell>
                        {canDraft ? (
                          <Input
                            aria-label={`Order quantity for ${nameOf(s)}`}
                            inputMode="decimal"
                            value={quantityFor(s)}
                            disabled={!s.supplier}
                            onChange={(e) =>
                              setQuantities({ ...quantities, [keyOf(s)]: e.target.value })
                            }
                          />
                        ) : (
                          formatQuantity(s.suggestedQuantity, s.product.unit)
                        )}
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </div>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader className="flex flex-row items-center justify-between gap-2">
          <CardTitle>Rules</CardTitle>
          {canRules && (
            <Button variant="outline" onClick={() => setEditing('new')}>
              New rule
            </Button>
          )}
        </CardHeader>
        <CardContent>
          {rules.isPending ? (
            <ListSkeleton rows={3} />
          ) : rules.isError ? (
            <ErrorState error={rules.error} onRetry={() => void rules.refetch()} />
          ) : rules.data.length === 0 ? (
            <EmptyState
              title="No reorder rules yet."
              description="Add a minimum and maximum for the products you never want to run out of."
            />
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Product</TableHead>
                  <TableHead>Warehouse</TableHead>
                  <TableHead className="text-right">Minimum</TableHead>
                  <TableHead className="text-right">Maximum</TableHead>
                  <TableHead>Preferred supplier</TableHead>
                  {canRules && <TableHead className="text-right">Actions</TableHead>}
                </TableRow>
              </TableHeader>
              <TableBody>
                {rules.data.map((r) => (
                  <TableRow key={r.id}>
                    <TableCell>
                      {r.product.sku} {r.product.name}
                    </TableCell>
                    <TableCell>{r.warehouse.code}</TableCell>
                    <TableCell className="text-right tabular-nums">
                      {formatQuantity(r.minQuantity)}
                    </TableCell>
                    <TableCell className="text-right tabular-nums">
                      {formatQuantity(r.maxQuantity)}
                    </TableCell>
                    <TableCell>{r.supplier?.name ?? '—'}</TableCell>
                    {canRules && (
                      <TableCell className="space-x-2 text-right">
                        <Button
                          size="sm"
                          variant="outline"
                          aria-label={`Edit rule for ${nameOf(r)}`}
                          onClick={() => setEditing(r)}
                        >
                          Edit
                        </Button>
                        <Button
                          size="sm"
                          variant="outline"
                          aria-label={`Delete rule for ${nameOf(r)}`}
                          onClick={() => {
                            setDeleteError(null)
                            setDeleting(r)
                          }}
                        >
                          Delete
                        </Button>
                      </TableCell>
                    )}
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>
      {editing && (
        <ReorderRuleDialog
          rule={editing === 'new' ? undefined : editing}
          onClose={() => setEditing(null)}
        />
      )}
      <ConfirmDialog
        open={deleting !== null}
        title={`Delete the rule for ${deleting ? nameOf(deleting) : ''}?`}
        description="The product stops appearing in reorder suggestions for that warehouse."
        confirmLabel="Delete rule"
        busy={busy}
        error={deleteError}
        onCancel={() => setDeleting(null)}
        onConfirm={() => deleting && void remove(deleting)}
      />
    </div>
  )
}
