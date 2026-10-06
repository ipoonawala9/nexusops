import { zodResolver } from '@hookform/resolvers/zod'
import { useQuery } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import { useForm } from 'react-hook-form'
import { Link, useNavigate, useSearchParams } from 'react-router'
import { z } from 'zod'
import { buttonVariants, Button } from '@/components/ui/button'
import { FormError } from '@/components/form/FormError'
import { TextField } from '@/components/form/TextField'
import { ListSkeleton } from '@/components/states'
import { useApi } from '@/lib/api/ApiContext'
import { ApiError } from '@/lib/api/client'
import { problemMessage } from '@/lib/api/problems'
import type { AcceptedInvitation, InvitationPreview } from '@/lib/api/types'
import { formatDateTime } from '@/lib/format'
import { AuthCard } from './AuthCard'
import { MESSAGES, newPasswordField, requiredText } from './schemas'

const schema = z
  .object({
    firstName: requiredText(80),
    lastName: requiredText(80),
    password: newPasswordField,
    repeat: z.string(),
  })
  .refine((v) => v.password === v.repeat, { path: ['repeat'], message: MESSAGES.passwordsDiffer })
type Values = z.infer<typeof schema>
const FIELDS = ['firstName', 'lastName', 'password'] as const

export function AcceptInvitationPage() {
  const api = useApi()
  const navigate = useNavigate()
  const [params] = useSearchParams()
  const [token] = useState(() => params.get('token') ?? '')
  const stripped = useRef(false)
  const [accepted, setAccepted] = useState<(AcceptedInvitation & { workspaceName: string }) | null>(
    null,
  )
  const [formError, setFormError] = useState<string | null>(null)

  useEffect(() => {
    if (stripped.current) return
    stripped.current = true
    navigate('/invite/accept', { replace: true }) // keep the token out of the address bar and history
  }, [navigate])

  const preview = useQuery({
    queryKey: ['invitation-preview', token],
    enabled: token.length > 0,
    staleTime: Infinity, // the token is single-use: never refetch it
    refetchOnWindowFocus: false,
    queryFn: () =>
      api.get<InvitationPreview>(`/invitations/preview?token=${encodeURIComponent(token)}`, {
        skipAuthRefresh: true,
      }),
  })

  const form = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: { firstName: '', lastName: '', password: '', repeat: '' },
  })

  const submit = form.handleSubmit(async ({ firstName, lastName, password }) => {
    setFormError(null)
    try {
      const result = await api.post<AcceptedInvitation>(
        '/invitations/accept',
        { token, firstName, lastName, password },
        { skipAuthRefresh: true },
      )
      // The token is consumed now; keep what the success screen needs so a later preview refetch can't affect it.
      setAccepted({ ...result, workspaceName: preview.data?.workspaceName ?? result.workspace })
    } catch (error) {
      if (!applyKnownErrors(error)) setFormError(problemMessage(error))
    }
  })

  function applyKnownErrors(error: unknown): boolean {
    if (!(error instanceof ApiError) || !error.problem.errors?.length) return false
    let applied = false
    for (const { field, message } of error.problem.errors) {
      const known = FIELDS.find((f) => f === field)
      if (known) {
        form.setError(known, { type: 'server', message })
        applied = true
      } else {
        setFormError(message) // e.g. email conflict: the invitee can't change the email here
        applied = true
      }
    }
    return applied
  }

  if (accepted) {
    const query = new URLSearchParams({ workspace: accepted.workspace, email: accepted.email })
    return (
      <AuthCard
        title="You're in"
        description={`Your account in ${accepted.workspaceName} is ready.`}
      >
        <Link to={`/login?${query.toString()}`} className={buttonVariants({ className: 'w-full' })}>
          Sign in to {accepted.workspaceName}
        </Link>
      </AuthCard>
    )
  }

  if (!token) {
    return (
      <AuthCard title="Invitation link incomplete">
        <p className="text-sm">Ask the person who invited you to send the invitation again.</p>
      </AuthCard>
    )
  }
  if (preview.isPending) {
    return (
      <AuthCard title="Opening your invitation">
        <ListSkeleton rows={3} />
      </AuthCard>
    )
  }
  if (preview.isError) {
    return (
      <AuthCard title="This invitation can't be used">
        <p role="alert" className="text-sm">
          {problemMessage(preview.error)}
        </p>
        <p className="text-sm text-muted-foreground">
          Ask the person who invited you for a new invitation.
        </p>
      </AuthCard>
    )
  }

  const invitation = preview.data

  return (
    <AuthCard
      title={`Join ${invitation.workspaceName}`}
      description={
        <>
          You were invited as <strong>{invitation.email}</strong> with the{' '}
          <strong>{invitation.roleName}</strong> role. The invitation expires{' '}
          {formatDateTime(invitation.expiresAt)}.
        </>
      }
    >
      <form noValidate onSubmit={submit} className="space-y-4">
        <div className="grid gap-4 sm:grid-cols-2">
          <TextField form={form} name="firstName" label="First name" autoComplete="given-name" />
          <TextField form={form} name="lastName" label="Last name" autoComplete="family-name" />
        </div>
        <TextField
          form={form}
          name="password"
          label="Password"
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
          Join {invitation.workspaceName}
        </Button>
      </form>
    </AuthCard>
  )
}
