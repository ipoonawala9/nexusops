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
import { Textarea } from '@/components/ui/textarea'
import { describedBy, Field } from '@/components/form/Field'
import { FormError } from '@/components/form/FormError'
import { useApi } from '@/lib/api/ApiContext'
import { applyFieldErrors, problemMessage } from '@/lib/api/problems'
import type { PlatformTenant } from '@/lib/api/types'

const REASON = 'Enter a reason between 1 and 500 characters.'
const schema = z.object({ reason: z.string().trim().min(1, REASON).max(500, REASON) })
type Values = z.infer<typeof schema>

export function StatusChangeDialog({
  tenant,
  action,
  onClose,
}: {
  tenant: PlatformTenant
  action: 'suspend' | 'reactivate'
  onClose: () => void
}) {
  const api = useApi()
  const queryClient = useQueryClient()
  const [error, setError] = useState<string | null>(null)
  const form = useForm<Values>({ resolver: zodResolver(schema), defaultValues: { reason: '' } })
  const reasonError = form.formState.errors.reason?.message
  const suspending = action === 'suspend'

  const submit = form.handleSubmit(async (values) => {
    setError(null)
    try {
      await api.post<PlatformTenant>(`/platform/tenants/${tenant.id}/${action}`, values)
      toast.success(`${tenant.name} ${suspending ? 'suspended' : 'reactivated'}.`)
      await queryClient.invalidateQueries({ queryKey: ['platform-tenants'] })
      onClose()
    } catch (e) {
      if (!applyFieldErrors(e, form.setError, ['reason'] as const)) setError(problemMessage(e))
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
          <DialogTitle>
            {suspending ? 'Suspend' : 'Reactivate'} {tenant.name}?
          </DialogTitle>
          <DialogDescription>
            {suspending
              ? 'Everyone in this workspace is blocked on their next request until it is reactivated. The reason is shown in its audit log.'
              : 'Members can sign in and work again. The reason is shown in its audit log.'}
          </DialogDescription>
        </DialogHeader>
        <form noValidate onSubmit={submit} className="space-y-4">
          <Field id="field-reason" label="Reason" error={reasonError}>
            <Textarea
              id="field-reason"
              rows={3}
              aria-invalid={reasonError ? true : undefined}
              aria-describedby={describedBy('field-reason', reasonError)}
              {...form.register('reason')}
            />
          </Field>
          <FormError message={error} />
          <DialogFooter>
            <Button type="button" variant="ghost" onClick={onClose}>
              Cancel
            </Button>
            <Button
              type="submit"
              variant={suspending ? 'destructive' : 'default'}
              disabled={form.formState.isSubmitting}
            >
              {suspending ? 'Suspend workspace' : 'Reactivate workspace'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
