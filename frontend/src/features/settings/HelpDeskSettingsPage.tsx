import { zodResolver } from '@hookform/resolvers/zod'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { toast } from 'sonner'
import { z } from 'zod'
import { ConfirmDialog } from '@/components/ConfirmDialog'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { FormError } from '@/components/form/FormError'
import { TextAreaField } from '@/components/form/TextAreaField'
import { TextField } from '@/components/form/TextField'
import { ErrorState, ListSkeleton, PageHeader } from '@/components/states'
import { requiredText } from '@/features/auth/schemas'
import { AgentSelect } from '@/features/helpdesk/AgentSelect'
import { invalidateHelpDesk } from '@/features/helpdesk/invalidation'
import { PRIORITIES, PRIORITY_LABELS } from '@/features/helpdesk/labels'
import { formatMinutes } from '@/features/helpdesk/sla'
import { useApi } from '@/lib/api/ApiContext'
import { ApiError } from '@/lib/api/client'
import { applyFieldErrors, problemMessage } from '@/lib/api/problems'
import type { CategoryView, SlaPolicyView } from '@/lib/api/types'

const categorySchema = z.object({
  name: requiredText(80),
  description: z.string().trim().max(500, 'Use at most 500 characters.'),
  defaultAssigneeId: z.string(),
})
type CategoryValues = z.infer<typeof categorySchema>
const CATEGORY_FIELDS = ['name', 'description', 'defaultAssigneeId'] as const

const MAX_MINUTES = 86400
const minutesField = z
  .string()
  .trim()
  .refine((v) => /^\d+$/.test(v) && Number(v) >= 1, 'Enter a number of minutes greater than 0.')
  .refine((v) => Number(v) <= MAX_MINUTES, 'Use at most 60 days.')
const slaSchema = z
  .object({ firstResponseMinutes: minutesField, resolutionMinutes: minutesField })
  .refine((v) => Number(v.resolutionMinutes) >= Number(v.firstResponseMinutes), {
    path: ['resolutionMinutes'],
    message: "Resolution can't be shorter than the first response.",
  })
type SlaValues = z.infer<typeof slaSchema>
const SLA_FIELDS = ['firstResponseMinutes', 'resolutionMinutes'] as const

/** Settings → HelpDesk (D5, D8): ticket categories with routing, and SLA targets per priority. */
export function HelpDeskSettingsPage() {
  return (
    <>
      <PageHeader
        title="HelpDesk"
        description="Categories route new tickets to a teammate. SLA targets set how fast tickets are answered and resolved."
      />
      <div className="space-y-8">
        <CategoriesSection />
        <SlaPoliciesSection />
      </div>
    </>
  )
}

function CategoriesSection() {
  const api = useApi()
  const queryClient = useQueryClient()
  const active = useQuery({
    queryKey: ['helpdesk', 'categories', false],
    queryFn: () => api.get<CategoryView[]>('/helpdesk/categories'),
  })
  const archived = useQuery({
    queryKey: ['helpdesk', 'categories', true],
    queryFn: () => api.get<CategoryView[]>('/helpdesk/categories?archived=true'),
  })
  const [editing, setEditing] = useState<CategoryView | 'new' | null>(null)
  const [archiving, setArchiving] = useState<CategoryView | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function act(action: () => Promise<unknown>, success: string) {
    setBusy(true)
    setError(null)
    try {
      await action()
      await invalidateHelpDesk(queryClient)
      toast.success(success)
      return true
    } catch (e) {
      setError(problemMessage(e))
      return false
    } finally {
      setBusy(false)
    }
  }

  return (
    <section aria-label="Ticket categories" className="space-y-3">
      <div className="flex items-center justify-between">
        <h2 className="text-lg font-semibold">Ticket categories</h2>
        <Button onClick={() => setEditing('new')}>New category</Button>
      </div>
      <FormError message={archiving ? null : error} />
      {active.isPending ? (
        <ListSkeleton />
      ) : active.isError ? (
        <ErrorState error={active.error} onRetry={() => void active.refetch()} />
      ) : (
        <div className="overflow-x-auto rounded-lg border">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Name</TableHead>
                <TableHead>Description</TableHead>
                <TableHead>Default assignee</TableHead>
                <TableHead className="text-right">Actions</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {[...active.data, ...(archived.data ?? [])].map((c) => (
                <TableRow key={c.id}>
                  <TableCell>
                    {c.name} {c.archivedAt && <Badge variant="outline">Archived</Badge>}
                  </TableCell>
                  <TableCell className="max-w-md whitespace-normal">
                    {c.description ?? '—'}
                  </TableCell>
                  <TableCell>{c.defaultAssignee?.name ?? '—'}</TableCell>
                  <TableCell className="space-x-2 text-right">
                    {c.archivedAt ? (
                      <Button
                        size="sm"
                        variant="outline"
                        disabled={busy}
                        aria-label={`Restore ${c.name}`}
                        onClick={() =>
                          void act(
                            () => api.post(`/helpdesk/categories/${c.id}/restore`),
                            `${c.name} restored.`,
                          )
                        }
                      >
                        Restore
                      </Button>
                    ) : (
                      <>
                        <Button
                          size="sm"
                          variant="outline"
                          aria-label={`Edit ${c.name}`}
                          onClick={() => setEditing(c)}
                        >
                          Edit
                        </Button>
                        <Button
                          size="sm"
                          variant="outline"
                          aria-label={`Archive ${c.name}`}
                          onClick={() => {
                            setError(null)
                            setArchiving(c)
                          }}
                        >
                          Archive
                        </Button>
                      </>
                    )}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      )}
      {editing && (
        <CategoryDialog
          category={editing === 'new' ? undefined : editing}
          onClose={() => setEditing(null)}
        />
      )}
      <ConfirmDialog
        open={archiving !== null}
        title={`Archive ${archiving?.name ?? ''}?`}
        description="New tickets can't use it. Tickets that already have it keep it."
        confirmLabel="Archive"
        busy={busy}
        error={error}
        onCancel={() => {
          setError(null)
          setArchiving(null)
        }}
        onConfirm={() => {
          if (!archiving) return
          void act(
            () => api.post(`/helpdesk/categories/${archiving.id}/archive`),
            `${archiving.name} archived.`,
          ).then((ok) => ok && setArchiving(null))
        }}
      />
    </section>
  )
}

function CategoryDialog({ category, onClose }: { category?: CategoryView; onClose: () => void }) {
  const api = useApi()
  const queryClient = useQueryClient()
  const [formError, setFormError] = useState<string | null>(null)
  const form = useForm<CategoryValues>({
    resolver: zodResolver(categorySchema),
    defaultValues: {
      name: category?.name ?? '',
      description: category?.description ?? '',
      defaultAssigneeId: category?.defaultAssignee?.id ?? '',
    },
  })
  const onSubmit = form.handleSubmit(async (values) => {
    setFormError(null)
    const body = {
      name: values.name.trim(),
      description: values.description.trim() || null,
      defaultAssigneeId: values.defaultAssigneeId || null,
      ...(category ? { version: category.version } : {}),
    }
    try {
      if (category) await api.put(`/helpdesk/categories/${category.id}`, body)
      else await api.post('/helpdesk/categories', body)
      await invalidateHelpDesk(queryClient)
      toast.success(category ? 'Changes saved.' : `${body.name} created.`)
      onClose()
    } catch (error) {
      if (category && error instanceof ApiError && error.status === 409)
        void invalidateHelpDesk(queryClient)
      if (!applyFieldErrors(error, form.setError, CATEGORY_FIELDS))
        setFormError(problemMessage(error))
    }
  })
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{category ? `Edit ${category.name}` : 'New category'}</DialogTitle>
          <DialogDescription>
            A new ticket in this category goes to its default assignee unless someone is chosen.
          </DialogDescription>
        </DialogHeader>
        <form noValidate onSubmit={(e) => void onSubmit(e)} className="space-y-4">
          <TextField form={form} name="name" label="Name" maxLength={80} />
          <TextAreaField form={form} name="description" label="Description" maxLength={500} />
          <AgentSelect
            id="field-defaultAssigneeId"
            label="Default assignee"
            blankLabel="Nobody"
            value={form.watch('defaultAssigneeId')}
            current={category?.defaultAssignee}
            error={form.formState.errors.defaultAssigneeId?.message}
            onChange={(id) => form.setValue('defaultAssigneeId', id)}
          />
          <FormError message={formError} />
          <DialogFooter>
            <Button type="button" variant="ghost" onClick={onClose}>
              Cancel
            </Button>
            <Button type="submit" disabled={form.formState.isSubmitting}>
              {category ? 'Save changes' : 'Create category'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

function SlaPoliciesSection() {
  const api = useApi()
  const policies = useQuery({
    queryKey: ['helpdesk', 'sla-policies'],
    queryFn: () => api.get<SlaPolicyView[]>('/helpdesk/sla-policies'),
  })
  const [editing, setEditing] = useState<SlaPolicyView | null>(null)
  const ordered = PRIORITIES.map((p) => policies.data?.find((x) => x.priority === p)).filter(
    (x): x is SlaPolicyView => !!x,
  )
  return (
    <section aria-label="SLA policies" className="space-y-3">
      <h2 className="text-lg font-semibold">SLA policies</h2>
      <p className="text-sm text-muted-foreground">
        Calendar time. Time waiting on the customer doesn&apos;t count towards resolution. Changes
        apply to new tickets and to tickets whose priority changes.
      </p>
      {policies.isPending ? (
        <ListSkeleton />
      ) : policies.isError ? (
        <ErrorState error={policies.error} onRetry={() => void policies.refetch()} />
      ) : (
        <div className="overflow-x-auto rounded-lg border">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Priority</TableHead>
                <TableHead>First response</TableHead>
                <TableHead>Resolution</TableHead>
                <TableHead className="text-right">Actions</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {ordered.map((p) => (
                <TableRow key={p.priority}>
                  <TableCell>{PRIORITY_LABELS[p.priority]}</TableCell>
                  <TableCell>{formatMinutes(p.firstResponseMinutes)}</TableCell>
                  <TableCell>{formatMinutes(p.resolutionMinutes)}</TableCell>
                  <TableCell className="text-right">
                    <Button
                      size="sm"
                      variant="outline"
                      aria-label={`Edit ${PRIORITY_LABELS[p.priority]} targets`}
                      onClick={() => setEditing(p)}
                    >
                      Edit
                    </Button>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      )}
      {editing && <SlaPolicyDialog policy={editing} onClose={() => setEditing(null)} />}
    </section>
  )
}

function SlaPolicyDialog({ policy, onClose }: { policy: SlaPolicyView; onClose: () => void }) {
  const api = useApi()
  const queryClient = useQueryClient()
  const [formError, setFormError] = useState<string | null>(null)
  const form = useForm<SlaValues>({
    resolver: zodResolver(slaSchema),
    defaultValues: {
      firstResponseMinutes: String(policy.firstResponseMinutes),
      resolutionMinutes: String(policy.resolutionMinutes),
    },
  })
  const label = PRIORITY_LABELS[policy.priority]
  const onSubmit = form.handleSubmit(async (values) => {
    setFormError(null)
    try {
      await api.put(`/helpdesk/sla-policies/${policy.priority}`, {
        firstResponseMinutes: Number(values.firstResponseMinutes),
        resolutionMinutes: Number(values.resolutionMinutes),
        version: policy.version,
      })
      await invalidateHelpDesk(queryClient)
      toast.success(`${label} targets saved.`)
      onClose()
    } catch (error) {
      if (error instanceof ApiError && error.status === 409) void invalidateHelpDesk(queryClient)
      if (!applyFieldErrors(error, form.setError, SLA_FIELDS)) setFormError(problemMessage(error))
    }
  })
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{label} targets</DialogTitle>
          <DialogDescription>
            Minutes from when the ticket is created (1 to 86 400).
          </DialogDescription>
        </DialogHeader>
        <form noValidate onSubmit={(e) => void onSubmit(e)} className="space-y-4">
          <TextField
            form={form}
            name="firstResponseMinutes"
            label="First response (minutes)"
            inputMode="numeric"
          />
          <TextField
            form={form}
            name="resolutionMinutes"
            label="Resolution (minutes)"
            inputMode="numeric"
          />
          <FormError message={formError} />
          <DialogFooter>
            <Button type="button" variant="ghost" onClick={onClose}>
              Cancel
            </Button>
            <Button type="submit" disabled={form.formState.isSubmitting}>
              Save targets
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
