import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'

export function HomePage() {
  return (
    <main className="mx-auto flex min-h-screen max-w-3xl items-center px-4">
      <Card className="w-full">
        <CardHeader>
          <CardTitle>
            <h1 className="text-2xl font-semibold">NexusOps</h1>
          </CardTitle>
          <CardDescription>One tenant, one business context, one permission model.</CardDescription>
        </CardHeader>
        <CardContent className="text-sm text-muted-foreground">
          Platform foundation is running. Sign-in arrives with the identity milestone.
        </CardContent>
      </Card>
    </main>
  )
}
