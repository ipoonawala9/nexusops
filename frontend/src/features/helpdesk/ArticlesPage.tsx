import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { NativeSelect } from '@/components/form/NativeSelect'
import { Pagination } from '@/components/Pagination'
import { EmptyState, ErrorState, ListSkeleton, PageHeader } from '@/components/states'
import { PERMISSIONS, useCan } from '@/features/auth/permissions'
import { useApi } from '@/lib/api/ApiContext'
import type { ArticleStatus, ArticleSummary, CategoryView, Page } from '@/lib/api/types'
import { formatDate } from '@/lib/format'
import { toQuery } from '@/lib/query'
import { ArticleFormDialog } from './ArticleFormDialog'
import { ARTICLE_STATUS_LABELS } from './labels'

const SIZE = 20

/** D13. Readers see published articles; writers also see drafts, and archived ones on request. */
export function ArticlesPage() {
  const api = useApi()
  const can = useCan()
  const navigate = useNavigate()
  const [params, setParams] = useSearchParams()
  const canManage = can(PERMISSIONS.articleManage)
  const q = params.get('q') ?? ''
  const status = canManage ? (params.get('status') ?? '') : ''
  const category = params.get('category') ?? ''
  const page = Math.max(0, Number(params.get('page') ?? '0') || 0)
  const [creating, setCreating] = useState(false)

  const articles = useQuery({
    queryKey: ['helpdesk', 'articles', { q, status, category, page }],
    queryFn: () =>
      api.get<Page<ArticleSummary>>(
        `/helpdesk/articles?${toQuery({ q, status, categoryId: category, page, size: SIZE })}`,
      ),
    placeholderData: keepPreviousData,
    refetchOnMount: 'always',
  })
  const categories = useQuery({
    queryKey: ['helpdesk', 'categories', false],
    queryFn: () => api.get<CategoryView[]>('/helpdesk/categories'),
    enabled: can(PERMISSIONS.ticketRead),
  })

  function update(next: Record<string, string>) {
    const merged = new URLSearchParams(params)
    for (const [key, value] of Object.entries(next)) {
      if (value) merged.set(key, value)
      else merged.delete(key)
    }
    setParams(merged, { replace: true })
  }

  function onSearch(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const value = new FormData(event.currentTarget).get('q')
    update({ q: typeof value === 'string' ? value.trim() : '', page: '' })
  }

  return (
    <>
      <PageHeader
        title="Knowledge base"
        description="Answers your team can reuse. Published articles are suggested on matching tickets."
        actions={canManage && <Button onClick={() => setCreating(true)}>New article</Button>}
      />
      <div className="mb-4 flex flex-wrap items-end gap-3">
        <form onSubmit={onSearch} className="flex items-end gap-2" role="search">
          <div className="space-y-1.5">
            <Label htmlFor="article-q">Search articles</Label>
            <Input
              id="article-q"
              name="q"
              defaultValue={q}
              placeholder="Words in the title or text"
            />
          </div>
          <Button type="submit" variant="outline">
            Search
          </Button>
        </form>
        {canManage && (
          <div className="space-y-1.5">
            <Label htmlFor="article-status">Status</Label>
            <NativeSelect
              id="article-status"
              value={status}
              onChange={(e) => update({ status: e.target.value, page: '' })}
            >
              <option value="">Drafts and published</option>
              {(Object.keys(ARTICLE_STATUS_LABELS) as ArticleStatus[]).map((s) => (
                <option key={s} value={s}>
                  {ARTICLE_STATUS_LABELS[s]}
                </option>
              ))}
            </NativeSelect>
          </div>
        )}
        {(categories.data ?? []).length > 0 && (
          <div className="space-y-1.5">
            <Label htmlFor="article-category">Category</Label>
            <NativeSelect
              id="article-category"
              value={category}
              onChange={(e) => update({ category: e.target.value, page: '' })}
            >
              <option value="">Any category</option>
              {(categories.data ?? []).map((c) => (
                <option key={c.id} value={c.id}>
                  {c.name}
                </option>
              ))}
            </NativeSelect>
          </div>
        )}
      </div>
      {articles.isPending ? (
        <ListSkeleton />
      ) : articles.isError ? (
        <ErrorState error={articles.error} onRetry={() => void articles.refetch()} />
      ) : articles.data.items.length === 0 ? (
        <EmptyState
          title={q || status || category ? 'No articles match.' : 'No articles yet.'}
          description={
            canManage
              ? 'Write down the answers you give most often.'
              : 'Published articles will appear here.'
          }
        />
      ) : (
        <>
          <ul className="divide-y rounded-lg border">
            {articles.data.items.map((a) => (
              <li key={a.id} className="space-y-1 p-4">
                <div className="flex flex-wrap items-center gap-2">
                  <Link
                    to={`/app/helpdesk/articles/${a.id}`}
                    className="font-medium underline-offset-4 hover:underline"
                  >
                    {a.title}
                  </Link>
                  {a.status !== 'PUBLISHED' && (
                    <Badge variant="outline">{ARTICLE_STATUS_LABELS[a.status]}</Badge>
                  )}
                  {a.category && (
                    <span className="text-sm text-muted-foreground">{a.category.name}</span>
                  )}
                  <span className="text-sm text-muted-foreground">
                    Updated {formatDate(a.updatedAt.slice(0, 10))}
                  </span>
                </div>
                <p className="text-sm text-muted-foreground">{a.excerpt}</p>
              </li>
            ))}
          </ul>
          <Pagination
            page={articles.data.page}
            size={articles.data.size}
            total={articles.data.total}
            onPage={(p) => update({ page: String(p) })}
          />
        </>
      )}
      {creating && (
        <ArticleFormDialog
          onClose={() => setCreating(false)}
          onSaved={(saved) => {
            setCreating(false)
            navigate(`/app/helpdesk/articles/${saved.id}`)
          }}
        />
      )}
    </>
  )
}
