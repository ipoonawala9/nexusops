import { zodResolver } from '@hookform/resolvers/zod'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { toast } from 'sonner'
import { z } from 'zod'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { describedBy, Field } from '@/components/form/Field'
import { FormError } from '@/components/form/FormError'
import { NativeSelect } from '@/components/form/NativeSelect'
import { TextAreaField } from '@/components/form/TextAreaField'
import { TextField } from '@/components/form/TextField'
import { requiredText } from '@/features/auth/schemas'
import { useApi } from '@/lib/api/ApiContext'
import { applyFieldErrors, problemMessage } from '@/lib/api/problems'
import type { AssigneeView, TaskView } from '@/lib/api/types'
import { toQuery } from '@/lib/query'
import { PRIORITY_LABELS } from './labels'

const schema = z.object({
  title: requiredText(200),
  description: z.string().max(5000, 'Use at most 5000 characters.'),
  priority: z.enum(['LOW', 'NORMAL', 'HIGH', 'URGENT']),
  dueOn: z.string(),
  assigneeId: z.string(),
})
type Values = z.infer<typeof schema>
const FIELDS = ['title', 'description', 'priority', 'dueOn', 'assigneeId'] as const

/** Create (optionally for a record) or edit a task. Managers only: the routes need collaboration.task.manage. */
export function TaskDialog({
  task,
  subject,
  onClose,
}: {
  task?: TaskView
  subject?: { type: string; id: string; label: string }
  onClose: () => void
}) {
  const api = useApi()
  const queryClient = useQueryClient()
  const [formError, setFormError] = useState<string | null>(null)
  const [search, setSearch] = useState('')
  const [picked, setPicked] = useState<{ id: string; name: string } | null>(null)
  const assignees = useQuery({
    queryKey: ['task-assignees', search.trim()],
    queryFn: () => api.get<AssigneeView[]>(`/tasks/assignees?${toQuery({ q: search.trim() })}`),
  })
  const form = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: {
      title: task?.title ?? '',
      description: task?.description ?? '',
      priority: task?.priority ?? 'NORMAL',
      dueOn: task?.dueOn ?? '',
      assigneeId: task?.assignee?.id ?? '',
    },
  })
  const target =
    subject ??
    (task?.subject ? { ...task.subject, label: task.subject.label ?? 'a record' } : undefined)
  const dueError = form.formState.errors.dueOn?.message
  const assigneeError = form.formState.errors.assigneeId?.message
  const assigneeId = form.watch('assigneeId')
  const options = assignees.data ?? []
  // The current and the picked assignee stay listed whatever the search returns, so the select never loses its value.
  const pinned = [task?.assignee, picked].filter(
    (member, index, all): member is { id: string; name: string } =>
      !!member && all.findIndex((other) => other?.id === member.id) === index,
  )

  const submit = form.handleSubmit(async (values) => {
    setFormError(null)
    const body = {
      title: values.title,
      description: values.description.trim() || null,
      priority: values.priority,
      dueOn: values.dueOn || null,
      assigneeId: values.assigneeId || null,
      subjectType: target?.type ?? null,
      subjectId: target?.id ?? null,
      ...(task ? { version: task.version } : {}),
    }
    try {
      if (task) await api.put<TaskView>(`/tasks/${task.id}`, body)
      else await api.post<TaskView>('/tasks', body)
      toast.success(task ? 'Task updated.' : 'Task created.')
      await queryClient.invalidateQueries({ queryKey: ['tasks'] })
      onClose()
    } catch (error) {
      if (!applyFieldErrors(error, form.setError, FIELDS)) setFormError(problemMessage(error))
    }
  })

  return (
    <Dialog
      open
      onOpenChange={(open) => {
        if (!open) onClose()
      }}
    >
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{task ? 'Edit task' : 'New task'}</DialogTitle>
          <DialogDescription>
            {target ? `For ${target.label}` : 'Assign it to yourself or a teammate.'}
          </DialogDescription>
        </DialogHeader>
        <form noValidate onSubmit={submit} className="space-y-4">
          <TextField form={form} name="title" label="Title" maxLength={200} />
          <TextAreaField form={form} name="description" label="Description" maxLength={5000} />
          <div className="grid gap-3 sm:grid-cols-2">
            <Field id="field-priority" label="Priority">
              <NativeSelect id="field-priority" {...form.register('priority')}>
                {Object.entries(PRIORITY_LABELS).map(([value, label]) => (
                  <option key={value} value={value}>
                    {label}
                  </option>
                ))}
              </NativeSelect>
            </Field>
            <Field id="field-dueOn" label="Due date" error={dueError}>
              <Input
                id="field-dueOn"
                type="date"
                aria-invalid={dueError ? true : undefined}
                aria-describedby={describedBy('field-dueOn', dueError)}
                {...form.register('dueOn')}
              />
            </Field>
          </div>
          <Field id="field-assigneeSearch" label="Find a teammate">
            <Input
              id="field-assigneeSearch"
              type="search"
              autoComplete="off"
              value={search}
              onChange={(event) => setSearch(event.target.value)}
            />
          </Field>
          <Field id="field-assigneeId" label="Assignee" error={assigneeError}>
            <NativeSelect
              id="field-assigneeId"
              value={assigneeId}
              aria-invalid={assigneeError ? true : undefined}
              aria-describedby={describedBy('field-assigneeId', assigneeError)}
              {...form.register('assigneeId', {
                onChange: (event: { target: { value: string } }) =>
                  setPicked(
                    options.find((member) => member.id === event.target.value) ??
                      pinned.find((member) => member.id === event.target.value) ??
                      null,
                  ),
              })}
            >
              <option value="">Unassigned</option>
              {pinned.map((member) => (
                <option key={member.id} value={member.id}>
                  {member.name}
                </option>
              ))}
              {options
                .filter((member) => !pinned.some((other) => other.id === member.id))
                .map((member) => (
                  <option key={member.id} value={member.id}>
                    {member.name}
                  </option>
                ))}
            </NativeSelect>
          </Field>
          <FormError
            message={formError ?? (assignees.isError ? problemMessage(assignees.error) : null)}
          />
          <DialogFooter>
            <Button type="button" variant="ghost" onClick={onClose}>
              Cancel
            </Button>
            <Button type="submit" disabled={form.formState.isSubmitting}>
              {task ? 'Save changes' : 'Create task'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
