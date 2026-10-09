import { zodResolver } from '@hookform/resolvers/zod'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { Link } from 'react-router'
import { z } from 'zod'
import { Button } from '@/components/ui/button'
import { FormError } from '@/components/form/FormError'
import { TextField } from '@/components/form/TextField'
import { useApi } from '@/lib/api/ApiContext'
import { problemMessage } from '@/lib/api/problems'
import { AuthCard } from './AuthCard'
import { emailField, requiredText } from './schemas'

const schema = z.object({ workspace: requiredText(64), email: emailField })
type Values = z.infer<typeof schema>

/**
 * POST /auth/password-reset/request answers 204 whether or not the workspace or address exists, so the
 * confirmation is deliberately conditional ("If an account matches…"). Failures (rate limit, outage) are shown.
 */
export function ForgotPasswordPage() {
  const api = useApi()
  const [sent, setSent] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const form = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: { workspace: '', email: '' },
  })

  const submit = form.handleSubmit(async (values) => {
    setError(null)
    try {
      await api.post('/auth/password-reset/request', values, { skipAuthRefresh: true })
      setSent(true)
    } catch (e) {
      setError(problemMessage(e))
    }
  })

  const backToSignIn = (
    <Link to="/login" className="underline">
      Back to sign in
    </Link>
  )

  if (sent) {
    return (
      <AuthCard
        title="Check your email"
        description="If an account matches, we've sent a link to reset your password. It expires in 1 hour."
        footer={backToSignIn}
      >
        <p className="text-sm text-muted-foreground">
          Nothing arrived? Check your spam folder, or try again in a few minutes.
        </p>
      </AuthCard>
    )
  }

  return (
    <AuthCard
      title="Forgot your password?"
      description="Enter your workspace and email and we'll send you a link to choose a new one."
      footer={backToSignIn}
    >
      <form noValidate onSubmit={submit} className="space-y-4">
        <TextField form={form} name="workspace" label="Workspace URL" autoComplete="organization" />
        <TextField form={form} name="email" label="Email" type="email" autoComplete="username" />
        <FormError message={error} />
        <Button type="submit" className="w-full" disabled={form.formState.isSubmitting}>
          Send reset link
        </Button>
      </form>
    </AuthCard>
  )
}
