import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useParams } from 'react-router'
import { toast } from 'sonner'
import { ConfirmDialog } from '@/components/ConfirmDialog'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { FormError } from '@/components/form/FormError'
import { ErrorState, ListSkeleton, PageHeader } from '@/components/states'
import { PERMISSIONS, useCan } from '@/features/auth/permissions'
import { DocumentsPanel } from '@/features/records/DocumentsPanel'
import { useApi } from '@/lib/api/ApiContext'
import { problemMessage } from '@/lib/api/problems'
import type { ArticleView } from '@/lib/api/types'
import { formatDateTime } from '@/lib/format'
import { ArticleFormDialog } from './ArticleFormDialog'
import { invalidateHelpDesk } from './invalidation'
import { ARTICLE_STATUS_LABELS } from './labels'

function ArticleDetail({ articleId }: { articleId: string }) {
  const api = useApi()
  const can = useCan()
  const queryClient = useQueryClient()
  const key = ['helpdesk', 'article', articleId]
  const article = useQuery({
    queryKey: key,
    queryFn: () => api.get<ArticleView>(`/helpdesk/articles/${articleId}`),
  })
  const [dialog, setDialog] = useState<'edit' | 'archive' | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function run(
    action: 'publish' | 'unpublish' | 'archive',
    version: number,
    success: string,
  ) {
    setBusy(true)
    setError(null)
    try {
      const updated = await api.post<ArticleView>(`/helpdesk/articles/${articleId}/${action}`, {
        version,
      })
      queryClient.setQueryData(key, updated)
      await invalidateHelpDesk(queryClient)
      toast.success(success)
      return true
    } catch (e) {
      setError(problemMessage(e))
      void queryClient.invalidateQueries({ queryKey: key })
      return false
    } finally {
      setBusy(false)
    }
  }

  const back = (
    <Link to="/app/helpdesk/articles" className="text-sm underline-offset-4 hover:underline">
      ← Knowledge base
    </Link>
  )
  if (article.isPending)
    return (
      <>
        {back}
        <ListSkeleton />
      </>
    )
  if (article.isError)
    return (
      <>
        {back}
        <ErrorState error={article.error} onRetry={() => void article.refetch()} />
      </>
    )
  const a = article.data
  const archived = a.status === 'ARCHIVED'
  const canManage = can(PERMISSIONS.articleManage) && !archived

  return (
    <div className="space-y-6">
      {back}
      <PageHeader
        title={a.title}
        description={[
          a.category?.name,
          a.author ? `by ${a.author.name}` : null,
          a.publishedAt ? `published ${formatDateTime(a.publishedAt)}` : null,
        ]
          .filter(Boolean)
          .join(' · ')}
        actions={
          <>
            <Badge variant={a.status === 'PUBLISHED' ? 'secondary' : 'outline'}>
              {ARTICLE_STATUS_LABELS[a.status]}
            </Badge>
            {canManage && (
              <>
                <Button variant="outline" size="sm" onClick={() => setDialog('edit')}>
                  Edit
                </Button>
                {a.status === 'DRAFT' ? (
                  <Button
                    size="sm"
                    disabled={busy}
                    onClick={() => void run('publish', a.version, 'Article published.')}
                  >
                    Publish
                  </Button>
                ) : (
                  <Button
                    variant="outline"
                    size="sm"
                    disabled={busy}
                    onClick={() =>
                      void run('unpublish', a.version, 'Article moved back to drafts.')
                    }
                  >
                    Unpublish
                  </Button>
                )}
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => {
                    setError(null)
                    setDialog('archive')
                  }}
                >
                  Archive
                </Button>
              </>
            )}
          </>
        }
      />
      {!dialog && <FormError message={error} />}
      <Card>
        <CardContent className="pt-6">
          <p className="whitespace-pre-wrap">{a.body}</p>
        </CardContent>
      </Card>
      <p className="text-sm text-muted-foreground">Last updated {formatDateTime(a.updatedAt)}</p>
      <DocumentsPanel subjectType="KB_ARTICLE" subjectId={a.id} archived={archived} />
      {dialog === 'edit' && (
        <ArticleFormDialog
          article={a}
          onClose={() => setDialog(null)}
          onSaved={() => setDialog(null)}
        />
      )}
      <ConfirmDialog
        open={dialog === 'archive'}
        title={`Archive ${a.title}?`}
        description="It stops being suggested on tickets and can't be published again."
        confirmLabel="Archive article"
        busy={busy}
        error={error}
        onCancel={() => setDialog(null)}
        onConfirm={() =>
          void run('archive', a.version, 'Article archived.').then((ok) => ok && setDialog(null))
        }
      />
    </div>
  )
}

/** Keyed by the route param so a different article starts with fresh dialog and error state. */
export function ArticleDetailPage() {
  const { articleId = '' } = useParams()
  return <ArticleDetail key={articleId} articleId={articleId} />
}
