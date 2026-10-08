import { zodResolver } from '@hookform/resolvers/zod'
import { useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
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
import { TextField } from '@/components/form/TextField'
import { useApi } from '@/lib/api/ApiContext'
import { applyFieldErrors, problemMessage } from '@/lib/api/problems'
import type { StockProductRef } from '@/lib/api/types'
import { invalidateInventory } from './invalidation'
import { ProductPicker } from './ProductPicker'
import { positiveQuantitySchema } from './quantity'
import { shortageText } from './shortages'
import { WarehouseSelect } from './WarehouseSelect'

const schema = z
  .object({
    productId: z.string().min(1, 'Choose a product.'),
    fromWarehouseId: z.string().min(1, 'Choose a warehouse.'),
    toWarehouseId: z.string().min(1, 'Choose a warehouse.'),
    quantity: positiveQuantitySchema,
    note: z.string().trim().max(200, 'Use at most 200 characters.'),
  })
  .refine((v) => v.fromWarehouseId !== v.toWarehouseId, {
    path: ['toWarehouseId'],
    message: 'Choose a different warehouse.',
  })
type Values = z.infer<typeof schema>
const FIELDS = ['productId', 'fromWarehouseId', 'toWarehouseId', 'quantity', 'note'] as const

/** Moves available stock between two active warehouses (D8). */
export function TransferStockDialog({
  product,
  fromWarehouseId,
  onClose,
}: {
  product?: StockProductRef
  fromWarehouseId?: string
  onClose: () => void
}) {
  const api = useApi()
  const queryClient = useQueryClient()
  const [formError, setFormError] = useState<string | null>(null)
  const form = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: {
      productId: product?.id ?? '',
      fromWarehouseId: fromWarehouseId ?? '',
      toWarehouseId: '',
      quantity: '',
      note: '',
    },
  })
  const errors = form.formState.errors
  const revalidate = { shouldValidate: form.formState.isSubmitted }
  const submit = form.handleSubmit(async (values) => {
    setFormError(null)
    try {
      await api.post('/inventory/transfers', {
        productId: values.productId,
        fromWarehouseId: values.fromWarehouseId,
        toWarehouseId: values.toWarehouseId,
        quantity: Number(values.quantity),
        note: values.note || null,
      })
      await invalidateInventory(queryClient)
      toast.success('Stock transferred.')
      onClose()
    } catch (error) {
      const shortage = shortageText(error)
      if (shortage) setFormError(shortage)
      else if (!applyFieldErrors(error, form.setError, FIELDS)) setFormError(problemMessage(error))
    }
  })
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-h-[90vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle>Transfer stock</DialogTitle>
          <DialogDescription>
            Only available stock (on hand minus reserved) can move.
          </DialogDescription>
        </DialogHeader>
        <form noValidate onSubmit={submit} className="space-y-4">
          {!product && (
            <ProductPicker
              id="field-productId"
              label="Product"
              value={form.watch('productId')}
              error={errors.productId?.message}
              onChange={(id) => form.setValue('productId', id, revalidate)}
            />
          )}
          <div className="grid gap-3 sm:grid-cols-2">
            <WarehouseSelect
              id="field-fromWarehouseId"
              label="From"
              blankLabel="Choose…"
              value={form.watch('fromWarehouseId')}
              error={errors.fromWarehouseId?.message}
              onChange={(id) => form.setValue('fromWarehouseId', id, revalidate)}
            />
            <WarehouseSelect
              id="field-toWarehouseId"
              label="To"
              blankLabel="Choose…"
              value={form.watch('toWarehouseId')}
              error={errors.toWarehouseId?.message}
              onChange={(id) => form.setValue('toWarehouseId', id, revalidate)}
            />
          </div>
          <TextField form={form} name="quantity" label="Quantity" inputMode="numeric" />
          <TextField form={form} name="note" label="Note" maxLength={200} />
          <FormError message={formError} />
          <DialogFooter>
            <Button type="button" variant="ghost" onClick={onClose}>
              Cancel
            </Button>
            <Button type="submit" disabled={form.formState.isSubmitting}>
              Transfer
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
