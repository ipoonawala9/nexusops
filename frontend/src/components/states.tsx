import type { ReactNode } from 'react'
import { Link } from 'react-router'
import { Button, buttonVariants } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { ApiError } from '@/lib/api/client'
import { problemMessage } from '@/lib/api/problems'

export function FullPageLoading({ label }: { label: string }) {
  return (
    <div role="status" aria-live="polite" className="flex min-h-screen items-center justify-center">
      <p className="text-sm text-muted-foreground">{label}</p>
    </div>
  )
}

export function ListSkeleton({ rows = 5 }: { rows?: number }) {
  return (
    <div role="status" aria-label="Loading" className="space-y-2">
      {Array.from({ length: rows }, (_, i) => (
        <Skeleton key={i} className="h-9 w-full" />
      ))}
    </div>
  )
}

export function ErrorState({ error, onRetry }: { error: unknown; onRetry?: () => void }) {
  const requestId = error instanceof ApiError ? error.problem.requestId : undefined
  return (
    <div role="alert" className="rounded-lg border border-destructive/30 p-4 text-sm">
      <p className="font-medium">{problemMessage(error)}</p>
      {requestId && <p className="mt-1 text-xs text-muted-foreground">Reference: {requestId}</p>}
      {onRetry && (
        <Button variant="outline" size="sm" className="mt-3" onClick={onRetry}>
          Retry
        </Button>
      )}
    </div>
  )
}

export function EmptyState({
  title,
  description,
  action,
}: {
  title: string
  description: string
  action?: ReactNode
}) {
  return (
    <div className="rounded-lg border border-dashed p-8 text-center">
      <p className="font-medium">{title}</p>
      <p className="mt-1 text-sm text-muted-foreground">{description}</p>
      {action && <div className="mt-4">{action}</div>}
    </div>
  )
}

export function NoAccess() {
  return (
    <section className="space-y-3">
      <h1 className="text-xl font-semibold">You don't have access to this page</h1>
      <p className="text-sm text-muted-foreground">
        Ask a workspace owner or admin to give your role the permission it needs.
      </p>
      <Link to="/app" className={buttonVariants({ variant: 'outline' })}>
        Back to overview
      </Link>
    </section>
  )
}

export function PageHeader({
  title,
  description,
  actions,
}: {
  title: string
  description?: string
  actions?: ReactNode
}) {
  return (
    <header className="mb-6 flex flex-wrap items-start justify-between gap-3">
      <div>
        <h1 className="text-xl font-semibold">{title}</h1>
        {description && <p className="mt-1 text-sm text-muted-foreground">{description}</p>}
      </div>
      {actions && <div className="flex gap-2">{actions}</div>}
    </header>
  )
}
