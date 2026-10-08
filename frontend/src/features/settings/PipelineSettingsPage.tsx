import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { toast } from 'sonner'
import { ConfirmDialog } from '@/components/ConfirmDialog'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { FormError } from '@/components/form/FormError'
import { ErrorState, ListSkeleton, PageHeader } from '@/components/states'
import { useApi } from '@/lib/api/ApiContext'
import { problemMessage } from '@/lib/api/problems'
import type { StageView } from '@/lib/api/types'

const KEY = ['crm-stages']

/** Settings → Pipeline (D5): open stages can be added, renamed, reordered and deleted; Won and Lost only renamed. */
export function PipelineSettingsPage() {
  const api = useApi()
  const queryClient = useQueryClient()
  const stages = useQuery({
    queryKey: KEY,
    queryFn: () => api.get<StageView[]>('/crm/pipeline/stages'),
  })
  const [error, setError] = useState<string | null>(null)
  const [deleting, setDeleting] = useState<StageView | null>(null)
  const [busy, setBusy] = useState(false)
  const [newName, setNewName] = useState('')
  const [newProbability, setNewProbability] = useState('50')

  async function refresh() {
    await queryClient.invalidateQueries({ queryKey: KEY })
    await queryClient.invalidateQueries({ queryKey: ['crm-board'] })
  }

  async function run(action: () => Promise<unknown>, success: string) {
    setError(null)
    setBusy(true)
    try {
      await action()
      await refresh()
      toast.success(success)
      return true
    } catch (e) {
      setError(problemMessage(e))
      return false
    } finally {
      setBusy(false)
    }
  }

  async function add(event: FormEvent) {
    event.preventDefault()
    const ok = await run(
      () =>
        api.post('/crm/pipeline/stages', { name: newName, probability: Number(newProbability) }),
      'Stage added.',
    )
    if (ok) setNewName('')
  }

  if (stages.isPending) return <ListSkeleton />
  if (stages.isError)
    return <ErrorState error={stages.error} onRetry={() => void stages.refetch()} />
  const open = stages.data.filter((s) => s.kind === 'OPEN')

  function move(stage: StageView, delta: number) {
    const ids = open.map((s) => s.id)
    const from = ids.indexOf(stage.id)
    const to = from + delta
    ;[ids[from], ids[to]] = [ids[to], ids[from]]
    void run(() => api.put('/crm/pipeline/stages/order', { stageIds: ids }), 'Order saved.')
  }

  return (
    <div className="space-y-6">
      <PageHeader
        title="Pipeline"
        description="The stages every opportunity moves through. Won and Lost are always last."
      />
      <FormError message={error} />
      <ol aria-label="Stages" className="space-y-2">
        {stages.data.map((stage) => {
          const index = open.findIndex((s) => s.id === stage.id)
          return (
            <StageRow
              key={`${stage.id}-${stage.version}`}
              stage={stage}
              busy={busy}
              canMoveUp={index > 0}
              canMoveDown={index >= 0 && index < open.length - 1}
              onMove={(delta) => move(stage, delta)}
              onSave={(name, probability) =>
                void run(
                  () =>
                    api.put(`/crm/pipeline/stages/${stage.id}`, {
                      name,
                      probability,
                      version: stage.version,
                    }),
                  'Stage saved.',
                )
              }
              onDelete={() => setDeleting(stage)}
            />
          )
        })}
      </ol>
      <form onSubmit={add} className="flex flex-wrap items-end gap-3 rounded-lg border p-4">
        <div className="space-y-1.5">
          <Label htmlFor="new-stage-name">New stage name</Label>
          <Input
            id="new-stage-name"
            maxLength={60}
            value={newName}
            onChange={(e) => setNewName(e.target.value)}
          />
        </div>
        <div className="space-y-1.5">
          <Label htmlFor="new-stage-probability">New stage probability (%)</Label>
          <Input
            id="new-stage-probability"
            type="number"
            min={0}
            max={100}
            className="w-28"
            value={newProbability}
            onChange={(e) => setNewProbability(e.target.value)}
          />
        </div>
        <Button type="submit" disabled={busy || !newName.trim()}>
          Add stage
        </Button>
      </form>
      <ConfirmDialog
        open={deleting !== null}
        title={`Delete ${deleting?.name ?? 'stage'}?`}
        description="Only a stage without opportunities can be deleted."
        confirmLabel="Delete"
        busy={busy}
        onCancel={() => setDeleting(null)}
        onConfirm={async () => {
          const stage = deleting
          setDeleting(null)
          if (stage) await run(() => api.del(`/crm/pipeline/stages/${stage.id}`), 'Stage deleted.')
        }}
      />
    </div>
  )
}

function StageRow({
  stage,
  busy,
  canMoveUp,
  canMoveDown,
  onMove,
  onSave,
  onDelete,
}: {
  stage: StageView
  busy: boolean
  canMoveUp: boolean
  canMoveDown: boolean
  onMove: (delta: number) => void
  onSave: (name: string, probability: number) => void
  onDelete: () => void
}) {
  const [name, setName] = useState(stage.name)
  const [probability, setProbability] = useState(String(stage.probability))
  const changed = name !== stage.name || probability !== String(stage.probability)
  const fixed = stage.kind !== 'OPEN'
  return (
    <li className="flex flex-wrap items-center gap-2 rounded-lg border p-3">
      <Input
        aria-label={`Stage name ${stage.name}`}
        className="w-48"
        maxLength={60}
        value={name}
        onChange={(e) => setName(e.target.value)}
      />
      <Input
        aria-label={`Probability of ${stage.name} (%)`}
        type="number"
        min={0}
        max={100}
        className="w-24"
        value={probability}
        onChange={(e) => setProbability(e.target.value)}
      />
      {fixed ? (
        <Badge variant="outline">{stage.kind === 'WON' ? 'Won stage' : 'Lost stage'}</Badge>
      ) : (
        <>
          <Button
            variant="outline"
            size="sm"
            aria-label={`Move ${stage.name} up`}
            disabled={busy || !canMoveUp}
            onClick={() => onMove(-1)}
          >
            ↑
          </Button>
          <Button
            variant="outline"
            size="sm"
            aria-label={`Move ${stage.name} down`}
            disabled={busy || !canMoveDown}
            onClick={() => onMove(1)}
          >
            ↓
          </Button>
        </>
      )}
      <Button
        size="sm"
        disabled={busy || !changed}
        onClick={() => onSave(name, Number(probability))}
      >
        Save
      </Button>
      {!fixed && (
        <Button
          variant="ghost"
          size="sm"
          aria-label={`Delete ${stage.name}`}
          disabled={busy}
          onClick={onDelete}
        >
          Delete
        </Button>
      )}
    </li>
  )
}
