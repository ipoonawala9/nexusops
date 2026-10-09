import { zodResolver } from '@hookform/resolvers/zod'
import { useEffect, useRef, useState } from 'react'
import { useForm } from 'react-hook-form'
import { Link, useNavigate, useSearchParams } from 'react-router'
import { z } from 'zod'
import { Button, buttonVariants } from '@/components/ui/button'
import { FormError } from '@/components/form/FormError'
import { TextField } from '@/components/form/TextField'
import { useApi } from '@/lib/api/ApiContext'
import { ApiError } from '@/lib/api/client'
import { applyFieldErrors, problemMessage } from '@/lib/api/problems'
import { AuthCard } from './AuthCard'
import { newPasswordField } from './schemas'

const INVALID_LINK = 'This reset link is invalid or has expired.'

const schema = z
  .object({ password: newPasswordField, repeat: z.string() })
  .refine((v) => v.password === v.repeat, { path: ['repeat'], message: "Passwords don't match." })
type Values = z.infer<typeof schema>
const FIELDS = ['password'] as const

type Outcome = 'form' | 'changed' | 'invalid'

export function ResetPasswordPage() {
  const api = useApi()
  const navigate = useNavigate()
  const [params] = useSearchParams()
  const [token] = useState(() => params.get('token') ?? '')
  const [outcome, setOutcome] = useState<Outcome>(token ? 'form' : 'invalid')
  const [formError, setFormError] = useState<string | null>(null)
  const stripped = useRef(false)
  const form = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: { password: '', repeat: '' },
  })

  useEffect(() => {
    if (!token || stripped.current) return
    stripped.current = true
    navigate('/reset-password', { replace: true }) // keep the token out of the address bar and history
  }, [navigate, token])

  const submit = form.handleSubmit(async ({ password }) => {
    setFormError(null)
    try {
      await api.post('/auth/password-reset', { token, password }, { skipAuthRefresh: true })
      setOutcome('changed')
    } catch (error) {
      if (applyFieldErrors(error, form.setError, FIELDS)) return
      if (error instanceof ApiError && error.status === 400) setOutcome('invalid')
      else setFormError(problemMessage(error))
    }
  })

  if (outcome === 'changed') {
    return (
      <AuthCard
        title="Password changed"
        description="You've been signed out everywhere. Sign in with your new password."
      >
        <Link to="/login" className={buttonVariants({ className: 'w-full' })}>
          Sign in
        </Link>
      </AuthCard>
    )
  }
  if (outcome === 'invalid') {
    return (
      <AuthCard
        title="We couldn't reset your password"
        footer={
          <Link to="/login" className="underline">
            Back to sign in
          </Link>
        }
      >
        <p role="alert" className="text-sm">
          {INVALID_LINK}
        </p>
        <Link to="/forgot-password" className={buttonVariants({ variant: 'outline' })}>
          Request a new link
        </Link>
      </AuthCard>
    )
  }

  return (
    <AuthCard title="Choose a new password">
      <form noValidate onSubmit={submit} className="space-y-4">
        <TextField
          form={form}
          name="password"
          label="New password"
          type="password"
          autoComplete="new-password"
          hint="At least 12 characters."
        />
        <TextField
          form={form}
          name="repeat"
          label="Repeat password"
          type="password"
          autoComplete="new-password"
        />
        <FormError message={formError} />
        <Button type="submit" className="w-full" disabled={form.formState.isSubmitting}>
          Set new password
        </Button>
      </form>
    </AuthCard>
  )
}
