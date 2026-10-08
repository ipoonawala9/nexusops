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
import { NativeSelect } from '@/components/form/NativeSelect'
import { TextAreaField } from '@/components/form/TextAreaField'
import { useApi } from '@/lib/api/ApiContext'
import { problemMessage } from '@/lib/api/problems'
import type { OpportunityView, StageRef, StageView } from '@/lib/api/types'
import { reasonSchema } from './schemas'

/** "Move to…" for one opportunity (board card or detail page). Lost needs a reason (D6). */
export function MoveStageControl({
  opportunity,
  stages,
  onMoved,
}: {
  opportunity: { id: string; name: string; stage: StageRef; version: number }
  stages: StageView[]
  onMoved?: (o: OpportunityView) => void
}) {
  const api = useApi()
  const queryClient = useQueryClient()
  const [busy, setBusy] = useState(false)
  const [losing, setLosing] = useState<StageView | null>(null)

  async function move(stage: StageView, lostReason?: string) {
    setBusy(true)
    try {
      const moved = await api.post<OpportunityView>(`/opportunities/${opportunity.id}/stage`, {
        stageId: stage.id,
        ...(lostReason ? { lostReason } : {}),
        version: opportunity.version,
      })
      queryClient.setQueryData(['opportunity', moved.id], moved)
      await queryClient.invalidateQueries({ queryKey: ['crm-board'] })
      await queryClient.invalidateQueries({ queryKey: ['opportunities'] })
      toast.success(`Moved to ${stage.name}.`)
      onMoved?.(moved)
      return true
    } catch (error) {
      toast.error(problemMessage(error))
      return false
    } finally {
      setBusy(false)
    }
  }

  return (
    <>
      <NativeSelect
        aria-label={`Move ${opportunity.name} to`}
        value={opportunity.stage.id}
        disabled={busy}
        onChange={(e) => {
          const stage = stages.find((s) => s.id === e.target.value)
          if (!stage) return
          if (stage.kind === 'LOST') setLosing(stage)
          else void move(stage)
        }}
      >
        {stages.map((s) => (
          <option key={s.id} value={s.id}>
            {s.name}
          </option>
        ))}
      </NativeSelect>
      {losing && (
        <LostDialog
          onClose={() => setLosing(null)}
          onSubmit={async (reason) => {
            if (await move(losing, reason)) setLosing(null)
          }}
        />
      )}
    </>
  )
}

function LostDialog({
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
          <DialogTitle>Mark as lost</DialogTitle>
          <DialogDescription>Why was this deal lost? It helps the next one.</DialogDescription>
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
              Mark as lost
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
