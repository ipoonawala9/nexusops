import { zodResolver } from '@hookform/resolvers/zod'
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
import { TextAreaField } from '@/components/form/TextAreaField'
import { useApi } from '@/lib/api/ApiContext'
import { problemMessage } from '@/lib/api/problems'
import type { LeadStatus, LeadView } from '@/lib/api/types'
import { reasonSchema } from './schemas'

/** D3: open statuses move freely; disqualifying needs a reason; a disqualified lead reopens as New. */
export function LeadStatusActions({
  lead,
  onChanged,
}: {
  lead: LeadView
  onChanged: (l: LeadView) => void
}) {
  const api = useApi()
  const [busy, setBusy] = useState(false)
  const [disqualifying, setDisqualifying] = useState(false)

  async function change(status: LeadStatus, reason?: string) {
    setBusy(true)
    try {
      const updated = await api.post<LeadView>(`/leads/${lead.id}/status`, {
        status,
        ...(reason ? { reason } : {}),
        version: lead.version,
      })
      onChanged(updated)
      return true
    } catch (error) {
      toast.error(problemMessage(error))
      return false
    } finally {
      setBusy(false)
    }
  }

  if (lead.status === 'CONVERTED') return null
  if (lead.status === 'DISQUALIFIED')
    return (
      <Button variant="outline" size="sm" disabled={busy} onClick={() => void change('NEW')}>
        Reopen
      </Button>
    )
  return (
    <>
      {lead.status !== 'CONTACTED' && (
        <Button
          variant="outline"
          size="sm"
          disabled={busy}
          onClick={() => void change('CONTACTED')}
        >
          Mark contacted
        </Button>
      )}
      {lead.status !== 'QUALIFIED' && (
        <Button
          variant="outline"
          size="sm"
          disabled={busy}
          onClick={() => void change('QUALIFIED')}
        >
          Mark qualified
        </Button>
      )}
      <Button variant="outline" size="sm" disabled={busy} onClick={() => setDisqualifying(true)}>
        Disqualify
      </Button>
      {disqualifying && (
        <DisqualifyDialog
          onClose={() => setDisqualifying(false)}
          onSubmit={async (reason) => {
            if (await change('DISQUALIFIED', reason)) setDisqualifying(false)
          }}
        />
      )}
    </>
  )
}

function DisqualifyDialog({
  onClose,
  onSubmit,
}: {
  onClose: () => void
  onSubmit: (reason: string) => Promise<void>
}) {
  const form = useForm<{ reason: string }>({
    resolver: zodResolver(reasonSchema),
    defaultValues: { reason: '' },
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
          <DialogTitle>Disqualify lead</DialogTitle>
          <DialogDescription>
            Say why, so the team knows. You can reopen it later.
          </DialogDescription>
        </DialogHeader>
        <form
          noValidate
          onSubmit={form.handleSubmit((v) => onSubmit(v.reason))}
          className="space-y-4"
        >
          <TextAreaField form={form} name="reason" label="Reason" maxLength={500} />
          <DialogFooter>
            <Button type="button" variant="ghost" onClick={onClose}>
              Cancel
            </Button>
            <Button type="submit" variant="destructive" disabled={form.formState.isSubmitting}>
              Disqualify
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
