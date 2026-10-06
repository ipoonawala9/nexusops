import type { ReactNode } from 'react'
import { Card, CardContent, CardDescription, CardHeader } from '@/components/ui/card'

export function AuthCard({
  title,
  description,
  footer,
  children,
}: {
  title: string
  description?: ReactNode
  footer?: ReactNode
  children: ReactNode
}) {
  return (
    <main className="flex min-h-screen items-center justify-center bg-muted/30 px-4 py-10">
      <Card className="w-full max-w-md">
        <CardHeader>
          <p className="text-sm font-medium text-muted-foreground">NexusOps</p>
          <h1 className="text-2xl font-semibold">{title}</h1>
          {description && <CardDescription>{description}</CardDescription>}
        </CardHeader>
        <CardContent className="space-y-4">
          {children}
          {footer && <div className="text-sm text-muted-foreground">{footer}</div>}
        </CardContent>
      </Card>
    </main>
  )
}
