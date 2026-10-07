import { zodResolver } from '@hookform/resolvers/zod'
import { keepPreviousData, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { toast } from 'sonner'
import { z } from 'zod'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Field } from '@/components/form/Field'
import { FormError } from '@/components/form/FormError'
import { NativeSelect } from '@/components/form/NativeSelect'
import { TextAreaField } from '@/components/form/TextAreaField'
import { TextField } from '@/components/form/TextField'
import { Pagination } from '@/components/Pagination'
import { ErrorState, ListSkeleton } from '@/components/states'
import { requiredText } from '@/features/auth/schemas'
import { PERMISSIONS, useCan } from '@/features/auth/permissions'
import { useApi } from '@/lib/api/ApiContext'
import { applyFieldErrors, problemMessage } from '@/lib/api/problems'
import type { ActivityType, ActivityView, Page, SubjectType } from '@/lib/api/types'
import { formatDateTime } from '@/lib/format'
import { toQuery } from '@/lib/query'

const TYPES: Record<ActivityType, string> = {
  NOTE: 'Note',
  CALL: 'Call',
  EMAIL: 'Email',
  MEETING: 'Meeting',
}

const schema = z.object({
  type: z.enum(['NOTE', 'CALL', 'EMAIL', 'MEETING']),
  summary: requiredText(200),
  body: z.string().max(10000, 'Use at most 10000 characters.'),
})
type Values = z.infer<typeof schema>

/** The append-only timeline of one record (ADR-0008, D8). */
export function ActivityPanel({
  subjectType,
  subjectId,
  archived,
}: {
  subjectType: SubjectType
  subjectId: string
  archived: boolean
}) {
  const api = useApi()
  const can = useCan()
  const queryClient = useQueryClient()
  const [page, setPage] = useState(0)
  const [formError, setFormError] = useState<string | null>(null)
  const canLog = can(PERMISSIONS.activityCreate) && !archived
  const activities = useQuery({
    queryKey: ['activities', subjectType, subjectId, page],
    queryFn: () =>
      api.get<Page<ActivityView>>(
        `/activities?${toQuery({ subjectType, subjectId, page, size: 20 })}`,
      ),
    placeholderData: keepPreviousData,
  })
  const form = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: { type: 'NOTE', summary: '', body: '' },
  })

  const submit = form.handleSubmit(async (values) => {
    setFormError(null)
    try {
      await api.post<ActivityView>('/activities', {
        subjectType,
        subjectId,
        type: values.type,
        summary: values.summary,
        body: values.body.trim() || null,
      })
      form.reset({ type: values.type, summary: '', body: '' })
      setPage(0)
      await queryClient.invalidateQueries({ queryKey: ['activities', subjectType, subjectId] })
      toast.success('Activity logged.')
    } catch (error) {
      if (!applyFieldErrors(error, form.setError, ['type', 'summary', 'body'] as const))
        setFormError(problemMessage(error))
    }
  })

  return (
    <section aria-label="Activity" className="space-y-3">
      <h2 className="text-lg font-semibold">Activity</h2>
      {canLog && (
        <form noValidate onSubmit={submit} className="space-y-3 rounded-lg border p-4">
          <div className="grid gap-3 sm:grid-cols-[10rem_1fr]">
            <Field id="field-type" label="Type">
              <NativeSelect id="field-type" {...form.register('type')}>
                {Object.entries(TYPES).map(([value, label]) => (
                  <option key={value} value={value}>
                    {label}
                  </option>
                ))}
              </NativeSelect>
            </Field>
            <TextField form={form} name="summary" label="Summary" maxLength={200} />
          </div>
          <TextAreaField form={form} name="body" label="Details" maxLength={10000} />
          <FormError message={formError} />
          <Button type="submit" size="sm" disabled={form.formState.isSubmitting}>
            Log activity
          </Button>
        </form>
      )}
      {activities.isPending ? (
        <ListSkeleton rows={3} />
      ) : activities.isError ? (
        <ErrorState error={activities.error} onRetry={() => void activities.refetch()} />
      ) : activities.data.items.length === 0 ? (
        <p className="text-sm text-muted-foreground">No activity yet.</p>
      ) : (
        <>
          <ol className="space-y-3">
            {activities.data.items.map((activity) => (
              <li key={activity.id} className="rounded-lg border p-3 text-sm">
                <div className="flex flex-wrap items-center gap-2">
                  <Badge variant="outline">{TYPES[activity.type]}</Badge>
                  <span className="font-medium">{activity.summary}</span>
                </div>
                {activity.body && <p className="mt-2 whitespace-pre-wrap">{activity.body}</p>}
                <p className="mt-2 text-xs text-muted-foreground">
                  {activity.author?.name ?? 'Former member'} · {formatDateTime(activity.occurredAt)}
                </p>
              </li>
            ))}
          </ol>
          <Pagination
            page={activities.data.page}
            size={activities.data.size}
            total={activities.data.total}
            onPage={setPage}
          />
        </>
      )}
    </section>
  )
}
