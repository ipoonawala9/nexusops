import { zodResolver } from '@hookform/resolvers/zod'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { z } from 'zod'
import { Button } from '@/components/ui/button'
import { FormError } from '@/components/form/FormError'
import { TextField } from '@/components/form/TextField'
import { useApi } from '@/lib/api/ApiContext'
import { problemMessage } from '@/lib/api/problems'
import { emailField, requiredText } from './schemas'

const schema = z.object({ workspace: requiredText(64), email: emailField })
type Values = z.infer<typeof schema>

/**
 * POST /auth/resend-verification always answers 202 (no account discovery), so the confirmation text
 * is deliberately conditional. With `known` values it is a single button; otherwise it asks for them.
 */
export function ResendVerification({ known }: { known?: Values }) {
  const api = useApi()
  const [sent, setSent] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const form = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: known ?? { workspace: '', email: '' },
  })

  const send = form.handleSubmit(async (values) => {
    setError(null)
    try {
      await api.post('/auth/resend-verification', values, { skipAuthRefresh: true })
      setSent(true)
    } catch (e) {
      setError(problemMessage(e))
    }
  })

  if (sent) {
    return (
      <p role="status" className="text-sm">
        If that account is waiting for verification, we've sent a new link. Check your inbox.
      </p>
    )
  }
  return (
    <form noValidate onSubmit={send} className="space-y-3">
      {!known && (
        <>
          <TextField
            form={form}
            name="workspace"
            label="Workspace URL"
            autoComplete="organization"
          />
          <TextField
            form={form}
            name="email"
            label="Work email"
            type="email"
            autoComplete="email"
          />
        </>
      )}
      <FormError message={error} />
      <Button type="submit" variant="outline" disabled={form.formState.isSubmitting}>
        Resend the email
      </Button>
    </form>
  )
}
