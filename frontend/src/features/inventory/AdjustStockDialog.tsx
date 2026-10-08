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
import { requiredText } from '@/features/auth/schemas'
import { useApi } from '@/lib/api/ApiContext'
import { applyFieldErrors, problemMessage } from '@/lib/api/problems'
import type { StockProductRef } from '@/lib/api/types'
import { invalidateInventory } from './invalidation'
import { ProductPicker } from './ProductPicker'
import { quantitySchema } from './quantity'
import { WarehouseSelect } from './WarehouseSelect'

const schema = z.object({
  productId: z.string().min(1, 'Choose a product.'),
  warehouseId: z.string().min(1, 'Choose a warehouse.'),
  countedQuantity: quantitySchema,
  reason: requiredText(200),
})
type Values = z.infer<typeof schema>
const FIELDS = ['productId', 'warehouseId', 'countedQuantity', 'reason'] as const

/** A stock count (D7): on hand becomes the counted quantity; the difference is one ledger row. */
export function AdjustStockDialog({
  product,
  warehouseId,
  onClose,
}: {
  product?: StockProductRef
  warehouseId?: string
  onClose: () => void
}) {
  const api = useApi()
  const queryClient = useQueryClient()
  const [formError, setFormError] = useState<string | null>(null)
  const form = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: {
      productId: product?.id ?? '',
      warehouseId: warehouseId ?? '',
      countedQuantity: '',
      reason: '',
    },
  })
  const errors = form.formState.errors
  const revalidate = { shouldValidate: form.formState.isSubmitted }
  const submit = form.handleSubmit(async (values) => {
    setFormError(null)
    try {
      await api.post('/inventory/adjustments', {
        productId: values.productId,
        warehouseId: values.warehouseId,
        countedQuantity: Number(values.countedQuantity),
        reason: values.reason,
      })
      await invalidateInventory(queryClient)
      toast.success('Count saved.')
      onClose()
    } catch (error) {
      if (!applyFieldErrors(error, form.setError, FIELDS)) setFormError(problemMessage(error))
    }
  })
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-h-[90vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle>Count stock</DialogTitle>
          <DialogDescription>
            Enter what is physically there. Reserved stock can't be counted away.
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
            name="countedQuantity"
            label="Counted quantity"
            inputMode="numeric"
          />
          <TextField form={form} name="reason" label="Reason" maxLength={200} />
          <FormError message={formError} />
          <DialogFooter>
            <Button type="button" variant="ghost" onClick={onClose}>
              Cancel
            </Button>
            <Button type="submit" disabled={form.formState.isSubmitting}>
              Save count
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
