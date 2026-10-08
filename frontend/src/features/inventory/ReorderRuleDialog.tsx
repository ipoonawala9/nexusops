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
import { PartyPicker } from '@/features/records/PartyPicker'
import { useApi } from '@/lib/api/ApiContext'
import { ApiError } from '@/lib/api/client'
import { applyFieldErrors, problemMessage } from '@/lib/api/problems'
import type { ReorderRuleView } from '@/lib/api/types'
import { invalidateInventory } from './invalidation'
import { ProductPicker } from './ProductPicker'
import { quantitySchema } from './quantity'
import { WarehouseSelect } from './WarehouseSelect'

const schema = z
  .object({
    productId: z.string().min(1, 'Choose a product.'),
    warehouseId: z.string().min(1, 'Choose a warehouse.'),
    minQuantity: quantitySchema,
    maxQuantity: quantitySchema,
    supplierId: z.string(),
  })
  .refine((v) => Number(v.maxQuantity) > Number(v.minQuantity), {
    path: ['maxQuantity'],
    message: 'Enter a maximum above the minimum.',
  })
type Values = z.infer<typeof schema>
const FIELDS = ['productId', 'warehouseId', 'minQuantity', 'maxQuantity', 'supplierId'] as const

/** One rule per product and warehouse (D11): below min → suggest up to max, from the preferred supplier. */
export function ReorderRuleDialog({
  rule,
  onClose,
}: {
  rule?: ReorderRuleView
  onClose: () => void
}) {
  const api = useApi()
  const queryClient = useQueryClient()
  const [formError, setFormError] = useState<string | null>(null)
  const form = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: {
      productId: rule?.product.id ?? '',
      warehouseId: rule?.warehouse.id ?? '',
      minQuantity: rule ? String(rule.minQuantity) : '',
      maxQuantity: rule ? String(rule.maxQuantity) : '',
      supplierId: rule?.supplier?.id ?? '',
    },
  })
  const errors = form.formState.errors
  const revalidate = { shouldValidate: form.formState.isSubmitted }
  const submit = form.handleSubmit(async (values) => {
    setFormError(null)
    try {
      await api.put('/inventory/reorder-rules', {
        productId: values.productId,
        warehouseId: values.warehouseId,
        minQuantity: Number(values.minQuantity),
        maxQuantity: Number(values.maxQuantity),
        supplierId: values.supplierId || null,
        ...(rule ? { version: rule.version } : {}),
      })
      await invalidateInventory(queryClient)
      toast.success('Reorder rule saved.')
      onClose()
    } catch (error) {
      // a conflict means this dialog holds a stale rule (edited or deleted meanwhile): say so, reload the
      // rules and close, so that reopening starts from the current rule
      if (error instanceof ApiError && error.status === 409) {
        toast.error(problemMessage(error))
        void queryClient.invalidateQueries({ queryKey: ['inventory', 'reorder-rules'] })
        onClose()
        return
      }
      if (!applyFieldErrors(error, form.setError, FIELDS)) setFormError(problemMessage(error))
    }
  })
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-h-[90vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle>
            {rule ? `Rule for ${rule.product.sku} at ${rule.warehouse.code}` : 'New reorder rule'}
          </DialogTitle>
          <DialogDescription>
            When available plus on order falls below the minimum, suggest ordering up to the
            maximum.
          </DialogDescription>
        </DialogHeader>
        <form noValidate onSubmit={submit} className="space-y-4">
          {!rule && (
            <>
              <ProductPicker
                id="field-productId"
                label="Product"
                value={form.watch('productId')}
                error={errors.productId?.message}
                onChange={(id) => form.setValue('productId', id, revalidate)}
              />
              <WarehouseSelect
                id="field-warehouseId"
                label="Warehouse"
                blankLabel="Choose…"
                value={form.watch('warehouseId')}
                error={errors.warehouseId?.message}
                onChange={(id) => form.setValue('warehouseId', id, revalidate)}
              />
            </>
          )}
          <div className="grid gap-3 sm:grid-cols-2">
            <TextField form={form} name="minQuantity" label="Minimum" inputMode="decimal" />
            <TextField form={form} name="maxQuantity" label="Maximum" inputMode="decimal" />
          </div>
          <PartyPicker
            id="field-supplierId"
            label="Preferred supplier"
            value={form.watch('supplierId')}
            current={rule?.supplier}
            error={errors.supplierId?.message}
            onChange={(id) => form.setValue('supplierId', id)}
          />
          <FormError message={formError} />
          <DialogFooter>
            <Button type="button" variant="ghost" onClick={onClose}>
              Cancel
            </Button>
            <Button type="submit" disabled={form.formState.isSubmitting}>
              Save rule
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
