import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState, type ChangeEvent } from 'react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
import { ConfirmDialog } from '@/components/ConfirmDialog'
import { ErrorState, ListSkeleton } from '@/components/states'
import { PERMISSIONS, useCan } from '@/features/auth/permissions'
import { useApi } from '@/lib/api/ApiContext'
import { problemMessage } from '@/lib/api/problems'
import type { DocumentView, SubjectType } from '@/lib/api/types'
import { saveBlob } from '@/lib/download'
import { formatBytes, formatDateTime } from '@/lib/format'
import { toQuery } from '@/lib/query'

const MAX_BYTES = 10 * 1024 * 1024 // server: DocumentService.MAX_BYTES

/** Files attached to one record (D10). Downloads always save to disk; nothing is rendered in the browser. */
export function DocumentsPanel({
  subjectType,
  subjectId,
  archived,
}: {
  subjectType: SubjectType
  subjectId: string
  archived: boolean
}) {
  const api = useApi()
  const can = useCan()
  const queryClient = useQueryClient()
  const [uploading, setUploading] = useState(false)
  const [deleting, setDeleting] = useState<DocumentView | null>(null)
  const [deleteBusy, setDeleteBusy] = useState(false)
  const [deleteError, setDeleteError] = useState<string | null>(null)
  const canRead = can(PERMISSIONS.documentRead)
  const canManage = can(PERMISSIONS.documentManage)
  const key = ['documents', subjectType, subjectId]
  const documents = useQuery({
    queryKey: key,
    queryFn: () => api.get<DocumentView[]>(`/documents?${toQuery({ subjectType, subjectId })}`),
    enabled: canRead,
  })
  if (!canRead) return null

  async function upload(event: ChangeEvent<HTMLInputElement>) {
    const input = event.currentTarget
    const file = input.files?.[0]
    if (!file) return
    if (file.size > MAX_BYTES) {
      toast.error('The file is larger than 10 MB.')
      input.value = ''
      return
    }
    const form = new FormData()
    form.set('subjectType', subjectType)
    form.set('subjectId', subjectId)
    form.set('file', file)
    setUploading(true)
    try {
      const saved = await api.upload<DocumentView>('/documents', form)
      toast.success(`Uploaded ${saved.fileName}.`)
      await queryClient.invalidateQueries({ queryKey: key })
    } catch (error) {
      toast.error(problemMessage(error))
    } finally {
      setUploading(false)
      input.value = ''
    }
  }

  async function download(document: DocumentView) {
    try {
      const file = await api.download(`/documents/${document.id}/content`)
      saveBlob(file.blob, file.fileName)
    } catch (error) {
      toast.error(problemMessage(error))
    }
  }

  async function remove() {
    if (!deleting) return
    setDeleteBusy(true)
    setDeleteError(null)
    try {
      await api.del(`/documents/${deleting.id}`)
      toast.success(`Deleted ${deleting.fileName}.`)
      setDeleting(null)
      await queryClient.invalidateQueries({ queryKey: key })
    } catch (error) {
      setDeleteError(problemMessage(error))
    } finally {
      setDeleteBusy(false)
    }
  }

  return (
    <section aria-label="Documents" className="space-y-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 className="text-lg font-semibold">Documents</h2>
        {canManage && !archived && (
          <div className="flex items-center gap-2">
            <Label htmlFor={`upload-${subjectId}`}>Upload file</Label>
            <input
              id={`upload-${subjectId}`}
              type="file"
              className="text-sm"
              disabled={uploading}
              onChange={(event) => void upload(event)}
            />
          </div>
        )}
      </div>
      {documents.isPending ? (
        <ListSkeleton rows={2} />
      ) : documents.isError ? (
        <ErrorState error={documents.error} onRetry={() => void documents.refetch()} />
      ) : documents.data.length === 0 ? (
        <p className="text-sm text-muted-foreground">No documents yet.</p>
      ) : (
        <ul className="divide-y rounded-lg border">
          {documents.data.map((document) => (
            <li key={document.id} className="flex flex-wrap items-center justify-between gap-2 p-3 text-sm">
              <div>
                <button
                  type="button"
                  className="font-medium underline-offset-4 hover:underline"
                  aria-label={`Download ${document.fileName}`}
                  onClick={() => void download(document)}
                >
                  {document.fileName}
                </button>
                <p className="text-xs text-muted-foreground">
                  {formatBytes(document.sizeBytes)} · {document.uploadedBy?.name ?? 'Former member'} ·{' '}
                  {formatDateTime(document.createdAt)}
                </p>
              </div>
              {canManage && (
                <Button
                  variant="ghost"
                  size="sm"
                  aria-label={`Delete ${document.fileName}`}
                  onClick={() => {
                    setDeleteError(null)
                    setDeleting(document)
                  }}
                >
                  Delete
                </Button>
              )}
            </li>
          ))}
        </ul>
      )}
      <ConfirmDialog
        open={deleting !== null}
        title={`Delete ${deleting?.fileName ?? 'file'}?`}
        description="The file is removed for everyone. This can't be undone."
        confirmLabel="Delete"
        onConfirm={() => void remove()}
        onCancel={() => setDeleting(null)}
        busy={deleteBusy}
        error={deleteError}
      />
    </section>
  )
}
