import { useState, type FormEvent } from 'react'
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
import type { PurchaseOrderView } from '@/lib/api/types'
import { formatQuantity, quantitySchema } from './quantity'

/** One receipt against the lines still due; blank or 0 skips a line (D9). */
export function ReceiveDialog({
  order,
  busy,
  error,
  onReceive,
  onClose,
}: {
  order: PurchaseOrderView
  busy: boolean
  error: string | null
  onReceive: (lines: Array<{ lineId: string; quantity: number }>) => Promise<boolean>
  onClose: () => void
}) {
  const due = order.lines.filter((l) => l.remainingQuantity > 0)
  const [values, setValues] = useState<Record<string, string>>(
    Object.fromEntries(due.map((l) => [l.id, String(l.remainingQuantity)])),
  )
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [formError, setFormError] = useState<string | null>(null)

  async function submit(event: FormEvent) {
    event.preventDefault()
    const found: Record<string, string> = {}
    const lines: Array<{ lineId: string; quantity: number }> = []
    for (const line of due) {
      const raw = (values[line.id] ?? '').trim()
      if (raw === '' || Number(raw) === 0) continue
      const parsed = quantitySchema.safeParse(raw)
      if (!parsed.success) found[line.id] = parsed.error.issues[0].message
      else if (Number(raw) > line.remainingQuantity)
        found[line.id] = `Receive at most ${formatQuantity(line.remainingQuantity)}.`
      else lines.push({ lineId: line.id, quantity: Number(raw) })
    }
    setErrors(found)
    if (Object.keys(found).length > 0) return
    if (lines.length === 0) {
      setFormError('Enter what arrived on at least one line.')
      return
    }
    setFormError(null)
    if (await onReceive(lines)) onClose()
  }

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-h-[90vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle>Receive {order.number}</DialogTitle>
          <DialogDescription>
            Into {order.warehouse.code}. What arrives is added to stock now.
          </DialogDescription>
        </DialogHeader>
        <form noValidate onSubmit={(e) => void submit(e)} className="space-y-4">
          {due.map((line) => {
            const id = `receive-${line.id}`
            const fieldError = errors[line.id]
            return (
              <Field
                key={line.id}
                id={id}
                label={`Receive ${line.product.sku} (${formatQuantity(line.remainingQuantity)} due)`}
                error={fieldError}
              >
                <Input
                  id={id}
                  inputMode="decimal"
                  value={values[line.id] ?? ''}
                  aria-invalid={fieldError ? true : undefined}
                  aria-describedby={describedBy(id, fieldError)}
                  onChange={(e) => setValues({ ...values, [line.id]: e.target.value })}
                />
              </Field>
            )
          })}
          <FormError message={formError ?? error} />
          <DialogFooter>
            <Button type="button" variant="ghost" onClick={onClose}>
              Cancel
            </Button>
            <Button type="submit" disabled={busy}>
              Record receipt
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
