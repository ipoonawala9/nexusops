import { isRouteErrorResponse, Link, useRouteError } from 'react-router'
import { Button, buttonVariants } from '@/components/ui/button'

function errorSummary(error: unknown): string {
  if (isRouteErrorResponse(error)) return `${error.status} ${error.statusText}`.trim()
  if (error instanceof Error) return error.message
  return String(error)
}

/**
 * Route `errorElement`: replaces React Router's default error page, which prints the stack even in production.
 * Development builds name the error (never the stack); production shows only the generic text.
 */
export function RouteError({
  home = '/',
  showDetails = import.meta.env.DEV,
}: {
  home?: string
  showDetails?: boolean
}) {
  const error = useRouteError()
  return (
    <main className="mx-auto flex min-h-screen max-w-3xl flex-col items-start justify-center gap-4 px-4">
      <h1 className="text-2xl font-semibold">Something went wrong</h1>
      <p className="text-sm text-muted-foreground">
        This page hit an unexpected problem. Reloading usually helps.
      </p>
      {showDetails && (
        <p className="rounded bg-muted px-3 py-2 font-mono text-xs">{errorSummary(error)}</p>
      )}
      <div className="flex gap-2">
        <Button onClick={() => window.location.reload()}>Reload</Button>
        <Link to={home} className={buttonVariants({ variant: 'outline' })}>
          Go home
        </Link>
      </div>
    </main>
  )
}
