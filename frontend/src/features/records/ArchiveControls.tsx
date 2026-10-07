import { useState } from 'react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { ConfirmDialog } from '@/components/ConfirmDialog'
import { problemMessage } from '@/lib/api/problems'

/** Archive (after confirmation) or restore a canonical record. Records are never deleted (ADR-0008). */
export function ArchiveControls({
  name,
  archived,
  onArchive,
  onRestore,
}: {
  name: string
  archived: boolean
  onArchive: () => Promise<unknown>
  onRestore: () => Promise<unknown>
}) {
  const [confirming, setConfirming] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function archive() {
    setBusy(true)
    setError(null)
    try {
      await onArchive()
      toast.success(`${name} archived.`)
      setConfirming(false)
    } catch (e) {
      setError(problemMessage(e))
    } finally {
      setBusy(false)
    }
  }

  async function restore() {
    setBusy(true)
    try {
      await onRestore()
      toast.success(`${name} restored.`)
    } catch (e) {
      toast.error(problemMessage(e))
    } finally {
      setBusy(false)
    }
  }

  if (archived) {
    return (
      <Button variant="outline" size="sm" disabled={busy} onClick={() => void restore()}>
        Restore
      </Button>
    )
  }
  return (
    <>
      <Button
        variant="outline"
        size="sm"
        onClick={() => {
          setError(null)
          setConfirming(true)
        }}
      >
        Archive
      </Button>
      <ConfirmDialog
        open={confirming}
        title={`Archive ${name}?`}
        description="It will be hidden from lists and can't take new activity, tasks or files. You can restore it later."
        confirmLabel="Archive"
        onConfirm={() => void archive()}
        onCancel={() => setConfirming(false)}
        busy={busy}
        error={error}
      />
    </>
  )
}
