import { zodResolver } from '@hookform/resolvers/zod'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { toast } from 'sonner'
import { z } from 'zod'
import { ConfirmDialog } from '@/components/ConfirmDialog'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { FormError } from '@/components/form/FormError'
import { TextField } from '@/components/form/TextField'
import { ErrorState, ListSkeleton, PageHeader } from '@/components/states'
import { requiredText } from '@/features/auth/schemas'
import { invalidateInventory } from '@/features/inventory/invalidation'
import { useApi } from '@/lib/api/ApiContext'
import { applyFieldErrors, problemMessage } from '@/lib/api/problems'
import type { WarehouseView } from '@/lib/api/types'

const schema = z.object({
  code: z
    .string()
    .trim()
    .regex(/^[A-Za-z0-9][A-Za-z0-9_-]{0,19}$/, 'Use up to 20 letters, digits, - or _.'),
  name: requiredText(100),
  address: z.string().trim().max(500, 'Use at most 500 characters.'),
})
type Values = z.infer<typeof schema>
const FIELDS = ['code', 'name', 'address'] as const

/** Settings → Warehouses (D3): add, rename, archive (refused while in use) and restore. */
export function WarehousesSettingsPage() {
  const api = useApi()
  const queryClient = useQueryClient()
  const active = useQuery({
    queryKey: ['inventory', 'warehouses', false],
    queryFn: () => api.get<WarehouseView[]>('/inventory/warehouses'),
  })
  const archived = useQuery({
    queryKey: ['inventory', 'warehouses', true],
    queryFn: () => api.get<WarehouseView[]>('/inventory/warehouses?archived=true'),
  })
  const [editing, setEditing] = useState<WarehouseView | 'new' | null>(null)
  const [archiving, setArchiving] = useState<WarehouseView | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function act(action: () => Promise<unknown>, success: string) {
    setBusy(true)
    setError(null)
    try {
      await action()
      await invalidateInventory(queryClient)
      toast.success(success)
      return true
    } catch (e) {
      setError(problemMessage(e))
      return false
    } finally {
      setBusy(false)
    }
  }

  if (active.isPending) return <ListSkeleton />
  if (active.isError)
    return <ErrorState error={active.error} onRetry={() => void active.refetch()} />
  const rows = [...active.data, ...(archived.data ?? [])]

  return (
    <>
      <PageHeader
        title="Warehouses"
        description="Where stock is kept. Every workspace keeps at least one active warehouse."
        actions={<Button onClick={() => setEditing('new')}>New warehouse</Button>}
      />
      <FormError message={archiving ? null : error} />
      <div className="overflow-x-auto rounded-lg border">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Code</TableHead>
              <TableHead>Name</TableHead>
              <TableHead>Address</TableHead>
              <TableHead className="text-right">Actions</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {rows.map((w) => (
              <TableRow key={w.id}>
                <TableCell className="font-mono">{w.code}</TableCell>
                <TableCell>
                  {w.name} {w.archivedAt && <Badge variant="outline">Archived</Badge>}
                </TableCell>
                <TableCell>{w.address ?? '—'}</TableCell>
                <TableCell className="space-x-2 text-right">
                  {w.archivedAt ? (
                    <Button
                      size="sm"
                      variant="outline"
                      disabled={busy}
                      aria-label={`Restore ${w.code}`}
                      onClick={() =>
                        void act(
                          () => api.post(`/inventory/warehouses/${w.id}/restore`),
                          `${w.code} restored.`,
                        )
                      }
                    >
                      Restore
                    </Button>
                  ) : (
                    <>
                      <Button
                        size="sm"
                        variant="outline"
                        aria-label={`Edit ${w.code}`}
                        onClick={() => setEditing(w)}
                      >
                        Edit
                      </Button>
                      <Button
                        size="sm"
                        variant="outline"
                        aria-label={`Archive ${w.code}`}
                        onClick={() => {
                          setError(null)
                          setArchiving(w)
                        }}
                      >
                        Archive
                      </Button>
                    </>
                  )}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>
      <ConfirmDialog
        open={archiving !== null}
        title={`Archive ${archiving?.code ?? ''}?`}
        description="Archived warehouses take no stock and can't be chosen on orders. You can restore it later."
        confirmLabel="Archive"
        busy={busy}
        error={error}
        onCancel={() => setArchiving(null)}
        onConfirm={() =>
          void act(
            () => api.post(`/inventory/warehouses/${archiving?.id}/archive`),
            `${archiving?.code} archived.`,
          ).then((ok) => ok && setArchiving(null))
        }
      />
      {editing && (
        <WarehouseDialog
          warehouse={editing === 'new' ? undefined : editing}
          onClose={() => setEditing(null)}
          onSaved={async () => {
            await invalidateInventory(queryClient)
            setEditing(null)
          }}
        />
      )}
    </>
  )
}

function WarehouseDialog({
  warehouse,
  onClose,
  onSaved,
}: {
  warehouse?: WarehouseView
  onClose: () => void
  onSaved: () => Promise<void>
}) {
  const api = useApi()
  const [formError, setFormError] = useState<string | null>(null)
  const form = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: {
      code: warehouse?.code ?? '',
      name: warehouse?.name ?? '',
      address: warehouse?.address ?? '',
    },
  })
  const submit = form.handleSubmit(async (values) => {
    setFormError(null)
    const body = {
      code: values.code,
      name: values.name,
      address: values.address || null,
      ...(warehouse ? { version: warehouse.version } : {}),
    }
    try {
      if (warehouse) await api.put(`/inventory/warehouses/${warehouse.id}`, body)
      else await api.post('/inventory/warehouses', body)
      toast.success(warehouse ? 'Changes saved.' : `${values.code.toUpperCase()} added.`)
      await onSaved()
    } catch (error) {
      if (!applyFieldErrors(error, form.setError, FIELDS)) setFormError(problemMessage(error))
    }
  })
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{warehouse ? `Edit ${warehouse.code}` : 'New warehouse'}</DialogTitle>
          <DialogDescription>Codes are unique in the workspace, ignoring case.</DialogDescription>
        </DialogHeader>
        <form noValidate onSubmit={submit} className="space-y-4">
          <TextField form={form} name="code" label="Code" maxLength={20} />
          <TextField form={form} name="name" label="Name" maxLength={100} />
          <TextField form={form} name="address" label="Address" maxLength={500} />
          <FormError message={formError} />
          <DialogFooter>
            <Button type="button" variant="ghost" onClick={onClose}>
              Cancel
            </Button>
            <Button type="submit" disabled={form.formState.isSubmitting}>
              {warehouse ? 'Save changes' : 'Add warehouse'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
