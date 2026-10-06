import { zodResolver } from '@hookform/resolvers/zod'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useMemo, useState } from 'react'
import { useForm } from 'react-hook-form'
import { toast } from 'sonner'
import { z } from 'zod'
import { Button } from '@/components/ui/button'
import { describedBy, Field } from '@/components/form/Field'
import { FormError } from '@/components/form/FormError'
import { NativeSelect } from '@/components/form/NativeSelect'
import { TextField } from '@/components/form/TextField'
import { ErrorState, ListSkeleton, PageHeader } from '@/components/states'
import { requiredText } from '@/features/auth/schemas'
import { PERMISSIONS, useCan } from '@/features/auth/permissions'
import { useTenantSession } from '@/features/auth/tenantSession'
import { useApi } from '@/lib/api/ApiContext'
import { applyFieldErrors, problemMessage } from '@/lib/api/problems'
import type { TenantSettings } from '@/lib/api/types'

const schema = z.object({
  name: requiredText(120),
  timezone: requiredText(64),
  locale: requiredText(35),
  currency: z
    .string()
    .trim()
    .regex(/^[A-Za-z]{3}$/, 'Use a 3-letter ISO currency code such as USD.'),
})
type Values = z.infer<typeof schema>
const FIELDS = ['name', 'timezone', 'locale', 'currency'] as const

export function WorkspaceSettingsPage() {
  const api = useApi()
  const settings = useQuery({
    queryKey: ['tenant'],
    queryFn: () => api.get<TenantSettings>('/tenant'),
  })
  return (
    <>
      <PageHeader title="Workspace" description="Name, regional defaults and plan." />
      {settings.isPending ? (
        <ListSkeleton rows={4} />
      ) : settings.isError ? (
        <ErrorState error={settings.error} onRetry={() => void settings.refetch()} />
      ) : (
        <WorkspaceForm settings={settings.data} />
      )}
    </>
  )
}

function WorkspaceForm({ settings }: { settings: TenantSettings }) {
  const api = useApi()
  const queryClient = useQueryClient()
  const session = useTenantSession()
  const canEdit = useCan()(PERMISSIONS.settingsUpdate)
  const [formError, setFormError] = useState<string | null>(null)
  const form = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: {
      name: settings.name,
      timezone: settings.timezone,
      locale: settings.locale,
      currency: settings.currency,
    },
  })
  const zones = useMemo(() => {
    const all = new Set(Intl.supportedValuesOf('timeZone'))
    all.add('UTC')
    all.add(settings.timezone)
    return [...all].sort()
  }, [settings.timezone])

  const submit = form.handleSubmit(async (values) => {
    setFormError(null)
    try {
      const updated = await api.patch<TenantSettings>('/tenant', {
        ...values,
        currency: values.currency.toUpperCase(),
      })
      queryClient.setQueryData(['tenant'], updated)
      form.reset({
        name: updated.name,
        timezone: updated.timezone,
        locale: updated.locale,
        currency: updated.currency,
      })
      toast.success('Workspace settings saved.')
      await session.reloadProfile()
    } catch (error) {
      if (!applyFieldErrors(error, form.setError, FIELDS)) setFormError(problemMessage(error))
    }
  })

  const timezoneError = form.formState.errors.timezone?.message
  return (
    <form noValidate onSubmit={submit} className="max-w-xl space-y-6">
      <dl className="grid grid-cols-3 gap-2 rounded-lg border p-4 text-sm">
        <dt className="text-muted-foreground">Workspace URL</dt>
        <dd className="col-span-2">{settings.slug}</dd>
        <dt className="text-muted-foreground">Plan</dt>
        <dd className="col-span-2">{settings.planCode}</dd>
        <dt className="text-muted-foreground">Status</dt>
        <dd className="col-span-2">{settings.status}</dd>
      </dl>
      {!canEdit && (
        <p className="text-sm text-muted-foreground">
          You can view these settings but not change them.
        </p>
      )}
      <fieldset disabled={!canEdit} className="space-y-4">
        <TextField form={form} name="name" label="Workspace name" />
        <Field id="field-timezone" label="Time zone" error={timezoneError}>
          <NativeSelect
            id="field-timezone"
            aria-invalid={timezoneError ? true : undefined}
            aria-describedby={describedBy('field-timezone', timezoneError)}
            {...form.register('timezone')}
          >
            {zones.map((zone) => (
              <option key={zone} value={zone}>
                {zone}
              </option>
            ))}
          </NativeSelect>
        </Field>
        <TextField
          form={form}
          name="locale"
          label="Locale"
          hint="A language tag such as en or en-IN."
        />
        <TextField
          form={form}
          name="currency"
          label="Currency"
          hint="ISO 4217, e.g. USD or INR."
          maxLength={3}
        />
      </fieldset>
      <FormError message={formError} />
      {canEdit && (
        <Button type="submit" disabled={form.formState.isSubmitting}>
          Save changes
        </Button>
      )}
    </form>
  )
}
