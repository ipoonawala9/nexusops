import { zodResolver } from '@hookform/resolvers/zod'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { Link } from 'react-router'
import { z } from 'zod'
import { Button } from '@/components/ui/button'
import { FormError } from '@/components/form/FormError'
import { TextField } from '@/components/form/TextField'
import { useApi } from '@/lib/api/ApiContext'
import { applyFieldErrors, problemMessage } from '@/lib/api/problems'
import { AuthCard } from './AuthCard'
import { ResendVerification } from './ResendVerification'
import { emailField, newPasswordField, requiredText, slugField, slugify } from './schemas'

const schema = z.object({
  workspaceName: requiredText(120),
  slug: slugField,
  firstName: requiredText(80),
  lastName: requiredText(80),
  email: emailField,
  password: newPasswordField,
})
type Values = z.infer<typeof schema>
const FIELDS = ['workspaceName', 'slug', 'firstName', 'lastName', 'email', 'password'] as const

export function SignupPage() {
  const api = useApi()
  const [created, setCreated] = useState<{ workspace: string; email: string } | null>(null)
  const [formError, setFormError] = useState<string | null>(null)
  const form = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: {
      workspaceName: '',
      slug: '',
      firstName: '',
      lastName: '',
      email: '',
      password: '',
    },
  })

  const submit = form.handleSubmit(async (values) => {
    setFormError(null)
    try {
      const result = await api.post<{ slug: string }>('/auth/signup', values, {
        skipAuthRefresh: true,
      })
      setCreated({ workspace: result.slug, email: values.email })
    } catch (error) {
      if (!applyFieldErrors(error, form.setError, FIELDS)) setFormError(problemMessage(error))
    }
  })

  if (created) {
    return (
      <AuthCard
        title="Check your email"
        description={`We sent a verification link to ${created.email}. Open it to activate your workspace.`}
        footer={
          <Link to="/login" className="underline">
            Back to sign in
          </Link>
        }
      >
        <ResendVerification known={created} />
      </AuthCard>
    )
  }

  return (
    <AuthCard
      title="Create your workspace"
      description="Start on the Free plan. You can invite your team once you're in."
      footer={
        <>
          Already have a workspace?{' '}
          <Link to="/login" className="underline">
            Sign in
          </Link>
        </>
      }
    >
      <form noValidate onSubmit={submit} className="space-y-4">
        <TextField
          form={form}
          name="workspaceName"
          label="Workspace name"
          autoComplete="organization"
          onValueChange={(name) => {
            if (!form.getFieldState('slug').isDirty) form.setValue('slug', slugify(name))
          }}
        />
        <TextField
          form={form}
          name="slug"
          label="Workspace URL"
          hint="Lowercase letters, numbers and hyphens. You'll use it to sign in."
        />
        <div className="grid gap-4 sm:grid-cols-2">
          <TextField form={form} name="firstName" label="First name" autoComplete="given-name" />
          <TextField form={form} name="lastName" label="Last name" autoComplete="family-name" />
        </div>
        <TextField form={form} name="email" label="Work email" type="email" autoComplete="email" />
        <TextField
          form={form}
          name="password"
          label="Password"
          type="password"
          autoComplete="new-password"
          hint="At least 12 characters."
        />
        <FormError message={formError} />
        <Button type="submit" className="w-full" disabled={form.formState.isSubmitting}>
          Create workspace
        </Button>
      </form>
    </AuthCard>
  )
}
