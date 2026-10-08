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
import { FormError } from '@/components/form/FormError'
import { TextAreaField } from '@/components/form/TextAreaField'
import { TextField } from '@/components/form/TextField'
import { PartyPicker } from '@/features/records/PartyPicker'
import { useApi } from '@/lib/api/ApiContext'
import { ApiError } from '@/lib/api/client'
import { applyFieldErrors, problemMessage } from '@/lib/api/problems'
import type { SalesOrderView } from '@/lib/api/types'
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
  customerId: z.string().min(1, 'Choose a customer.'),
  warehouseId: z.string().min(1, 'Choose a warehouse.'),
  currency: z
    .string()
    .trim()
    .refine((v) => v === '' || /^[A-Za-z]{3}$/.test(v), 'Use a 3-letter currency code like USD.'),
  notes: z.string().max(2000, 'Use at most 2000 characters.'),
})
type Values = z.infer<typeof schema>
const FIELDS = ['customerId', 'warehouseId', 'currency', 'notes'] as const

/** Create or edit a DRAFT sales order (D10). A blank price uses the product's list price. */
export function SalesOrderFormDialog({
  order,
  onClose,
  onSaved,
}: {
  order?: SalesOrderView
  onClose: () => void
  onSaved: (saved: SalesOrderView) => void
}) {
  const api = useApi()
  const queryClient = useQueryClient()
  const [formError, setFormError] = useState<string | null>(null)
  const [lines, setLines] = useState<LineDraft[]>(
    order
      ? order.lines.map((l) => newLine(l.product, String(l.quantity), String(l.unitPrice)))
      : [newLine()],
  )
  const [lineErrors, setLineErrors] = useState<LineErrors>({})
  const form = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: {
      customerId: order?.customer?.id ?? '',
      warehouseId: order?.warehouse.id ?? '',
      currency: order?.currency ?? '',
      notes: order?.notes ?? '',
    },
  })
  const errors = form.formState.errors
  const revalidate = { shouldValidate: form.formState.isSubmitted }

  function submit(event: FormEvent<HTMLFormElement>) {
    const found = validateLines(lines, 'unitPrice', false)
    setLineErrors(found)
    void form.handleSubmit(async (values) => {
      if (Object.keys(found).length > 0) return
      setFormError(null)
      const body = {
        customerId: values.customerId,
        warehouseId: values.warehouseId,
        currency: values.currency === '' ? null : values.currency.toUpperCase(),
        notes: values.notes.trim() || null,
        lines: lines.map((l) => ({
          productId: l.product?.id,
          quantity: Number(l.quantity),
          unitPrice: l.price.trim() === '' ? null : Number(l.price),
        })),
        ...(order ? { version: order.version } : {}),
      }
      try {
        const saved = order
          ? await api.put<SalesOrderView>(`/sales-orders/${order.id}`, body)
          : await api.post<SalesOrderView>('/sales-orders', body)
        queryClient.setQueryData(['inventory', 'sales-order', saved.id], saved)
        await invalidateInventory(queryClient)
        toast.success(order ? 'Changes saved.' : `${saved.number} created.`)
        onSaved(saved)
      } catch (error) {
        // a conflict means the cached order is stale: reload it so the next save carries the current version
        if (order && error instanceof ApiError && error.status === 409)
          void queryClient.invalidateQueries({ queryKey: ['inventory', 'sales-order', order.id] })
        const fromServer = serverLineErrors(error)
        if (fromServer) setLineErrors(fromServer)
        const header = applyFieldErrors(error, form.setError, FIELDS)
        if (!fromServer && !header) setFormError(problemMessage(error))
      }
    })(event)
  }

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>{order ? `Edit ${order.number}` : 'New sales order'}</DialogTitle>
          <DialogDescription>
            A draft reserves nothing. Confirming reserves every line, or tells you what is short.
          </DialogDescription>
        </DialogHeader>
        <form noValidate onSubmit={submit} className="space-y-4">
          <PartyPicker
            id="field-customerId"
            label="Customer"
            value={form.watch('customerId')}
            current={order?.customer}
            error={errors.customerId?.message}
            noneLabel="Choose…"
            onChange={(id) => form.setValue('customerId', id, revalidate)}
          />
          <div className="grid gap-3 sm:grid-cols-2">
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
          </div>
          <OrderLinesEditor
            lines={lines}
            onChange={setLines}
            priceField="unitPrice"
            priceLabel="Unit price"
            priceHint="Blank uses the list price"
            errors={lineErrors}
          />
          <TextAreaField form={form} name="notes" label="Notes" maxLength={2000} />
          <FormError message={formError} />
          <DialogFooter>
            <Button type="button" variant="ghost" onClick={onClose}>
              Cancel
            </Button>
            <Button type="submit" disabled={form.formState.isSubmitting}>
              {order ? 'Save changes' : 'Create sales order'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
