import { zodResolver } from '@hookform/resolvers/zod'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { Link, useSearchParams } from 'react-router'
import { z } from 'zod'
import { Button } from '@/components/ui/button'
import { FormError } from '@/components/form/FormError'
import { TextField } from '@/components/form/TextField'
import { ApiError } from '@/lib/api/client'
import { problemMessage } from '@/lib/api/problems'
import { AuthCard } from './AuthCard'
import { ResendVerification } from './ResendVerification'
import { emailField, MESSAGES, requiredText } from './schemas'
import { useTenantSession } from './tenantSession'

const schema = z.object({
  workspace: requiredText(64),
  email: emailField,
  password: z.string().min(1, MESSAGES.required),
})
type Values = z.infer<typeof schema>

/** After a successful sign-in, RedirectIfTenantSignedIn sends the user to ?next (or /app). */
export function LoginPage() {
  const session = useTenantSession()
  const [params] = useSearchParams()
  const [error, setError] = useState<string | null>(null)
  const [unverified, setUnverified] = useState<{ workspace: string; email: string } | null>(null)
  const form = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: {
      workspace: params.get('workspace') ?? '',
      email: params.get('email') ?? '',
      password: '',
    },
  })

  const submit = form.handleSubmit(async (values) => {
    setError(null)
    setUnverified(null)
    try {
      await session.login(values)
    } catch (e) {
      setError(problemMessage(e))
      if (e instanceof ApiError && e.problem.detail === 'Email address not verified.') {
        setUnverified({ workspace: values.workspace, email: values.email })
      }
    }
  })

  return (
    <AuthCard
      title="Sign in"
      description={params.get('next') ? 'Please sign in to continue.' : 'Welcome back.'}
      footer={
        <>
          New to NexusOps?{' '}
          <Link to="/signup" className="underline">
            Create a workspace
          </Link>
        </>
      }
    >
      <form noValidate onSubmit={submit} className="space-y-4">
        <TextField form={form} name="workspace" label="Workspace URL" autoComplete="organization" />
        <TextField form={form} name="email" label="Email" type="email" autoComplete="username" />
        <TextField
          form={form}
          name="password"
          label="Password"
          type="password"
          autoComplete="current-password"
        />
        <p className="text-sm">
          <Link to="/forgot-password" className="underline">
            Forgot password?
          </Link>
        </p>
        <FormError message={error} />
        <Button type="submit" className="w-full" disabled={form.formState.isSubmitting}>
          Sign in
        </Button>
      </form>
      {unverified && <ResendVerification known={unverified} />}
    </AuthCard>
  )
}
