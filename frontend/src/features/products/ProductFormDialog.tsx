import { zodResolver } from '@hookform/resolvers/zod'
import { useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Field } from '@/components/form/Field'
import { FormError } from '@/components/form/FormError'
import { NativeSelect } from '@/components/form/NativeSelect'
import { TextAreaField } from '@/components/form/TextAreaField'
import { TextField } from '@/components/form/TextField'
import { useApi } from '@/lib/api/ApiContext'
import { applyFieldErrors, problemMessage } from '@/lib/api/problems'
import type { ProductView } from '@/lib/api/types'
import { KIND_LABELS, productSchema, type ProductValues } from './schemas'

const FIELDS = ['sku', 'name', 'description', 'kind', 'unit', 'listPrice', 'currency'] as const

export function ProductFormDialog({
  product,
  onClose,
  onSaved,
}: {
  product?: ProductView
  onClose: () => void
  onSaved: (saved: ProductView) => void
}) {
  const api = useApi()
  const queryClient = useQueryClient()
  const [formError, setFormError] = useState<string | null>(null)
  const form = useForm<ProductValues>({
    resolver: zodResolver(productSchema),
    defaultValues: {
      sku: product?.sku ?? '',
      name: product?.name ?? '',
      description: product?.description ?? '',
      kind: product?.kind ?? 'GOODS',
      unit: product?.unit ?? 'each',
      listPrice: product?.listPrice == null ? '' : String(product.listPrice),
      currency: product?.currency ?? '',
    },
  })

  const submit = form.handleSubmit(async (values) => {
    setFormError(null)
    const body = {
      sku: values.sku,
      name: values.name,
      description: values.description.trim() || null,
      kind: values.kind,
      unit: values.unit.trim() || 'each',
      listPrice: values.listPrice === '' ? null : Number(values.listPrice),
      currency: values.currency === '' ? null : values.currency.toUpperCase(),
      ...(product ? { version: product.version } : {}),
    }
    try {
      const saved = product
        ? await api.put<ProductView>(`/products/${product.id}`, body)
        : await api.post<ProductView>('/products', body)
      queryClient.setQueryData(['product', saved.id], saved)
      await queryClient.invalidateQueries({ queryKey: ['products'] })
      toast.success(product ? 'Changes saved.' : `${saved.name} added.`)
      onSaved(saved)
    } catch (error) {
      if (!applyFieldErrors(error, form.setError, FIELDS)) setFormError(problemMessage(error))
    }
  })

  return (
    <Dialog
      open
      onOpenChange={(open) => {
        if (!open) onClose()
      }}
    >
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{product ? `Edit ${product.name}` : 'New product'}</DialogTitle>
          <DialogDescription>
            SKUs are unique in this workspace and can't be reused.
          </DialogDescription>
        </DialogHeader>
        <form noValidate onSubmit={submit} className="space-y-4">
          <div className="grid gap-3 sm:grid-cols-2">
            <TextField form={form} name="sku" label="SKU" maxLength={64} />
            <TextField form={form} name="name" label="Name" maxLength={200} />
          </div>
          <TextAreaField form={form} name="description" label="Description" maxLength={2000} />
          <div className="grid gap-3 sm:grid-cols-2">
            <Field id="field-kind" label="Kind">
              <NativeSelect id="field-kind" {...form.register('kind')}>
                <option value="GOODS">{KIND_LABELS.GOODS}</option>
                <option value="SERVICE">{KIND_LABELS.SERVICE}</option>
              </NativeSelect>
            </Field>
            <TextField form={form} name="unit" label="Unit" maxLength={20} />
          </div>
          <div className="grid gap-3 sm:grid-cols-2">
            <TextField form={form} name="listPrice" label="List price" inputMode="text" />
            <TextField
              form={form}
              name="currency"
              label="Currency"
              maxLength={3}
              hint="Defaults to the workspace currency"
            />
          </div>
          <FormError message={formError} />
          <DialogFooter>
            <Button type="button" variant="ghost" onClick={onClose}>
              Cancel
            </Button>
            <Button type="submit" disabled={form.formState.isSubmitting}>
              {product ? 'Save changes' : 'Create product'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
