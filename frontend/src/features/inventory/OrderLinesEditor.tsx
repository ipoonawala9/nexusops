import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { describedBy, Field } from '@/components/form/Field'
import { ApiError } from '@/lib/api/client'
import type { StockProductRef } from '@/lib/api/types'
import { ProductPicker } from './ProductPicker'
import { positiveQuantitySchema, priceSchema } from './quantity'

export interface LineDraft {
  key: string
  product: StockProductRef | null
  quantity: string
  price: string
}

/** Keys are the server's field names: 'lines', 'lines[0].quantity', … */
export type LineErrors = Record<string, string>

let nextKey = 0

export function newLine(
  product: StockProductRef | null = null,
  quantity = '',
  price = '',
): LineDraft {
  nextKey += 1
  return { key: `line-${nextKey}`, product, quantity, price }
}

export function validateLines(
  lines: LineDraft[],
  priceField: 'unitCost' | 'unitPrice',
  priceRequired: boolean,
): LineErrors {
  const errors: LineErrors = {}
  if (lines.length === 0) errors.lines = 'Add at least one line.'
  if (lines.length > 100) errors.lines = 'Use at most 100 lines.'
  const seen = new Set<string>()
  lines.forEach((line, i) => {
    if (!line.product) errors[`lines[${i}].productId`] = 'Choose a product.'
    else if (seen.has(line.product.id))
      errors[`lines[${i}].productId`] = 'This product is already on the order.'
    else seen.add(line.product.id)
    const quantity = positiveQuantitySchema.safeParse(line.quantity)
    if (!quantity.success) errors[`lines[${i}].quantity`] = quantity.error.issues[0].message
    if (line.price.trim() === '') {
      if (priceRequired) errors[`lines[${i}].${priceField}`] = 'Enter an amount.'
    } else {
      const price = priceSchema.safeParse(line.price)
      if (!price.success) errors[`lines[${i}].${priceField}`] = price.error.issues[0].message
    }
  })
  return errors
}

/** The server's `lines…` field errors, or null when the error carries none. */
export function serverLineErrors(error: unknown): LineErrors | null {
  if (!(error instanceof ApiError)) return null
  const found = (error.problem.errors ?? []).filter(
    (e) => e.field === 'lines' || e.field.startsWith('lines['),
  )
  if (found.length === 0) return null
  return Object.fromEntries(found.map((e) => [e.field, e.message]))
}

export function OrderLinesEditor({
  lines,
  onChange,
  priceField,
  priceLabel,
  priceHint,
  errors,
}: {
  lines: LineDraft[]
  onChange: (lines: LineDraft[]) => void
  priceField: 'unitCost' | 'unitPrice'
  priceLabel: string
  priceHint?: string
  errors: LineErrors
}) {
  function update(index: number, patch: Partial<LineDraft>) {
    onChange(lines.map((line, i) => (i === index ? { ...line, ...patch } : line)))
  }
  return (
    <fieldset className="space-y-3">
      <legend className="text-sm font-medium">Lines</legend>
      {lines.map((line, i) => {
        const quantityId = `line-${i}-quantity`
        const priceId = `line-${i}-price`
        const quantityError = errors[`lines[${i}].quantity`]
        const priceError = errors[`lines[${i}].${priceField}`]
        return (
          <div key={line.key} className="space-y-2 rounded-md border p-3">
            <ProductPicker
              id={`line-${i}-product`}
              label={`Line ${i + 1} product`}
              value={line.product?.id ?? ''}
              current={line.product}
              error={errors[`lines[${i}].productId`]}
              onChange={(_, product) => update(i, { product })}
            />
            <div className="grid items-end gap-3 sm:grid-cols-[1fr_1fr_auto]">
              <Field id={quantityId} label={`Line ${i + 1} quantity`} error={quantityError}>
                <Input
                  id={quantityId}
                  inputMode="decimal"
                  value={line.quantity}
                  aria-invalid={quantityError ? true : undefined}
                  aria-describedby={describedBy(quantityId, quantityError)}
                  onChange={(e) => update(i, { quantity: e.target.value })}
                />
              </Field>
              <Field
                id={priceId}
                label={`Line ${i + 1} ${priceLabel.toLowerCase()}`}
                error={priceError}
                hint={priceHint}
              >
                <Input
                  id={priceId}
                  inputMode="decimal"
                  value={line.price}
                  aria-invalid={priceError ? true : undefined}
                  aria-describedby={describedBy(priceId, priceError, priceHint)}
                  onChange={(e) => update(i, { price: e.target.value })}
                />
              </Field>
              <Button
                type="button"
                variant="ghost"
                aria-label={`Remove line ${i + 1}`}
                onClick={() => onChange(lines.filter((_, j) => j !== i))}
              >
                Remove
              </Button>
            </div>
          </div>
        )
      })}
      {errors.lines && (
        <p role="alert" className="text-sm text-destructive">
          {errors.lines}
        </p>
      )}
      <Button type="button" variant="outline" onClick={() => onChange([...lines, newLine()])}>
        Add line
      </Button>
    </fieldset>
  )
}
