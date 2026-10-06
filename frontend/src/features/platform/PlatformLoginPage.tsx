import { zodResolver } from '@hookform/resolvers/zod'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { z } from 'zod'
import { Button } from '@/components/ui/button'
import { FormError } from '@/components/form/FormError'
import { TextField } from '@/components/form/TextField'
import { AuthCard } from '@/features/auth/AuthCard'
import { emailField, MESSAGES } from '@/features/auth/schemas'
import { problemMessage } from '@/lib/api/problems'
import { usePlatformSession } from './platformSession'

const schema = z.object({
  email: emailField,
  password: z.string().min(1, MESSAGES.required),
  // Six digits, spaces allowed (authenticator apps show "123 456"); stripped before sending.
  code: z.string().regex(/^(\s*\d){6}\s*$/, 'Enter the 6-digit code.'),
})
type Values = z.infer<typeof schema>

export function PlatformLoginPage() {
  const session = usePlatformSession()
  const [error, setError] = useState<string | null>(null)
  const form = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: { email: '', password: '', code: '' },
  })

  const submit = form.handleSubmit(async (values) => {
    setError(null)
    try {
      await session.login({ ...values, code: values.code.replace(/\s/g, '') })
    } catch (e) {
      setError(problemMessage(e))
    }
  })

  return (
    <AuthCard
      title="Staff sign-in"
      description="NexusOps platform console. Use your password and authenticator app."
    >
      <form noValidate onSubmit={submit} className="space-y-4">
        <TextField form={form} name="email" label="Email" type="email" autoComplete="username" />
        <TextField
          form={form}
          name="password"
          label="Password"
          type="password"
          autoComplete="current-password"
        />
        <TextField
          form={form}
          name="code"
          label="Authenticator code"
          inputMode="numeric"
          autoComplete="one-time-code"
          maxLength={7}
        />
        <FormError message={error} />
        <Button type="submit" className="w-full" disabled={form.formState.isSubmitting}>
          Sign in
        </Button>
      </form>
    </AuthCard>
  )
}
