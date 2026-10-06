import { useEffect, useRef, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router'
import { buttonVariants } from '@/components/ui/button'
import { useApi } from '@/lib/api/ApiContext'
import { problemMessage } from '@/lib/api/problems'
import { AuthCard } from './AuthCard'
import { ResendVerification } from './ResendVerification'

type Outcome = { kind: 'verifying' } | { kind: 'verified' } | { kind: 'failed'; message: string }

export function VerifyEmailPage() {
  const api = useApi()
  const navigate = useNavigate()
  const [params] = useSearchParams()
  const [token] = useState(() => params.get('token'))
  const [outcome, setOutcome] = useState<Outcome>(() =>
    token
      ? { kind: 'verifying' }
      : { kind: 'failed', message: 'This verification link is incomplete.' },
  )
  const started = useRef(false)

  useEffect(() => {
    if (!token || started.current) return // single-use token: StrictMode re-runs effects, refs persist
    started.current = true
    navigate('/verify-email', { replace: true }) // drop the token from the address bar and history
    api
      .post('/auth/verify-email', { token }, { skipAuthRefresh: true })
      .then(() => setOutcome({ kind: 'verified' }))
      .catch((error: unknown) => setOutcome({ kind: 'failed', message: problemMessage(error) }))
  }, [api, navigate, token])

  if (outcome.kind === 'verifying') {
    return (
      <AuthCard title="Verifying your email">
        <p role="status" className="text-sm text-muted-foreground">
          One moment…
        </p>
      </AuthCard>
    )
  }
  if (outcome.kind === 'verified') {
    return (
      <AuthCard title="Email verified" description="Your workspace is ready.">
        <Link to="/login" className={buttonVariants({ className: 'w-full' })}>
          Sign in
        </Link>
      </AuthCard>
    )
  }
  return (
    <AuthCard
      title="We couldn't verify your email"
      footer={
        <Link to="/login" className="underline">
          Back to sign in
        </Link>
      }
    >
      <p role="alert" className="text-sm">
        {outcome.message}
      </p>
      <p className="text-sm text-muted-foreground">Request a fresh link:</p>
      <ResendVerification />
    </AuthCard>
  )
}
