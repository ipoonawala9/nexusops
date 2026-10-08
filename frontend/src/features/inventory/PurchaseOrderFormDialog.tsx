import { zodResolver } from '@hookform/resolvers/zod'
import { useQueryClient } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { useForm } from 'react-hook-form'
import { toast } from 'sonner'
import { z } from 'zod'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { describedBy, Field } from '@/components/form/Field'
import { FormError } from '@/components/form/FormError'
import { TextAreaField } from '@/components/form/TextAreaField'
import { TextField } from '@/components/form/TextField'
import { PartyPicker } from '@/features/records/PartyPicker'
import { useApi } from '@/lib/api/ApiContext'
import { ApiError } from '@/lib/api/client'
import { applyFieldErrors, problemMessage } from '@/lib/api/problems'
import type { PurchaseOrderView } from '@/lib/api/types'
import { invalidateInventory } from './invalidation'
import {
  newLine,
  OrderLinesEditor,
  serverLineErrors,
  validateLines,
  type LineDraft,
  type LineErrors,
} from './OrderLinesEditor'
import { WarehouseSelect } from './WarehouseSelect'

const schema = z.object({
  supplierId: z.string().min(1, 'Choose a supplier.'),
  warehouseId: z.string().min(1, 'Choose a warehouse.'),
  currency: z
    .string()
    .trim()
    .refine((v) => v === '' || /^[A-Za-z]{3}$/.test(v), 'Use a 3-letter currency code like USD.'),
  expectedOn: z.string(),
  notes: z.string().max(2000, 'Use at most 2000 characters.'),
})
type Values = z.infer<typeof schema>
const FIELDS = ['supplierId', 'warehouseId', 'currency', 'expectedOn', 'notes'] as const

/** Create or edit a DRAFT purchase order (D9). */
export function PurchaseOrderFormDialog({
  order,
  onClose,
  onSaved,
}: {
  order?: PurchaseOrderView
  onClose: () => void
  onSaved: (saved: PurchaseOrderView) => void
}) {
  const api = useApi()
  const queryClient = useQueryClient()
  const [formError, setFormError] = useState<string | null>(null)
  const [lines, setLines] = useState<LineDraft[]>(
    order
      ? order.lines.map((l) => newLine(l.product, String(l.quantity), String(l.unitCost)))
      : [newLine()],
  )
  const [lineErrors, setLineErrors] = useState<LineErrors>({})
  const form = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: {
      supplierId: order?.supplier?.id ?? '',
      warehouseId: order?.warehouse.id ?? '',
      currency: order?.currency ?? '',
      expectedOn: order?.expectedOn ?? '',
      notes: order?.notes ?? '',
    },
  })
  const errors = form.formState.errors
  const revalidate = { shouldValidate: form.formState.isSubmitted }

  function submit(event: FormEvent<HTMLFormElement>) {
    const found = validateLines(lines, 'unitCost', true)
    setLineErrors(found)
    void form.handleSubmit(async (values) => {
      if (Object.keys(found).length > 0) return
      setFormError(null)
      const body = {
        supplierId: values.supplierId,
        warehouseId: values.warehouseId,
        currency: values.currency === '' ? null : values.currency.toUpperCase(),
        expectedOn: values.expectedOn || null,
        notes: values.notes.trim() || null,
        lines: lines.map((l) => ({
          productId: l.product?.id,
          quantity: Number(l.quantity),
          unitCost: Number(l.price),
        })),
        ...(order ? { version: order.version } : {}),
      }
      try {
        const saved = order
          ? await api.put<PurchaseOrderView>(`/purchase-orders/${order.id}`, body)
          : await api.post<PurchaseOrderView>('/purchase-orders', body)
        queryClient.setQueryData(['inventory', 'purchase-order', saved.id], saved)
        await invalidateInventory(queryClient)
        toast.success(order ? 'Changes saved.' : `${saved.number} created.`)
        onSaved(saved)
      } catch (error) {
        // a conflict means the cached order is stale: reload it so the next save carries the current version
        if (order && error instanceof ApiError && error.status === 409)
          void queryClient.invalidateQueries({
            queryKey: ['inventory', 'purchase-order', order.id],
          })
        const fromServer = serverLineErrors(error)
        if (fromServer) setLineErrors(fromServer)
        const header = applyFieldErrors(error, form.setError, FIELDS)
        if (!fromServer && !header) setFormError(problemMessage(error))
      }
    })(event)
  }

  const expectedError = errors.expectedOn?.message
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>{order ? `Edit ${order.number}` : 'New purchase order'}</DialogTitle>
          <DialogDescription>
            A draft changes nothing until you place it. Receipts then add the stock.
          </DialogDescription>
        </DialogHeader>
        <form noValidate onSubmit={submit} className="space-y-4">
          <PartyPicker
            id="field-supplierId"
            label="Supplier"
            value={form.watch('supplierId')}
            current={order?.supplier}
            error={errors.supplierId?.message}
            noneLabel="Choose…"
            onChange={(id) => form.setValue('supplierId', id, revalidate)}
          />
          <div className="grid gap-3 sm:grid-cols-3">
            <WarehouseSelect
              id="field-warehouseId"
              label="Warehouse"
              blankLabel="Choose…"
              value={form.watch('warehouseId')}
              error={errors.warehouseId?.message}
              onChange={(id) => form.setValue('warehouseId', id, revalidate)}
            />
            <TextField
              form={form}
              name="currency"
              label="Currency"
              maxLength={3}
              hint="Defaults to the workspace currency"
            />
            <Field id="field-expectedOn" label="Expected on" error={expectedError}>
              <Input
                id="field-expectedOn"
                type="date"
                aria-invalid={expectedError ? true : undefined}
                aria-describedby={describedBy('field-expectedOn', expectedError)}
                {...form.register('expectedOn')}
              />
            </Field>
          </div>
          <OrderLinesEditor
            lines={lines}
            onChange={setLines}
            priceField="unitCost"
            priceLabel="Unit cost"
            errors={lineErrors}
          />
          <TextAreaField form={form} name="notes" label="Notes" maxLength={2000} />
          <FormError message={formError} />
          <DialogFooter>
            <Button type="button" variant="ghost" onClick={onClose}>
              Cancel
            </Button>
            <Button type="submit" disabled={form.formState.isSubmitting}>
              {order ? 'Save changes' : 'Create purchase order'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
