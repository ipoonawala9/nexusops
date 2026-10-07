import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { useSearchParams } from 'react-router'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { NativeSelect } from '@/components/form/NativeSelect'
import { Pagination } from '@/components/Pagination'
import { EmptyState, ErrorState, ListSkeleton, PageHeader } from '@/components/states'
import { PERMISSIONS, useCan } from '@/features/auth/permissions'
import { SubjectLink } from '@/features/records/SubjectLink'
import { useApi } from '@/lib/api/ApiContext'
import type { Page, TaskView } from '@/lib/api/types'
import { formatDate, todayIso } from '@/lib/format'
import { toQuery } from '@/lib/query'
import { isOpen, PRIORITY_LABELS } from './labels'
import { TaskDialog } from './TaskDialog'
import { TaskStatusSelect } from './TaskStatusSelect'

const SIZE = 20
const VIEWS: Record<string, string> = { mine: 'me', all: '', unassigned: 'unassigned' }
const STATUSES: Record<string, string> = { open: 'OPEN,IN_PROGRESS', done: 'DONE', cancelled: 'CANCELLED', any: '' }

export function TasksPage() {
  const api = useApi()
  const can = useCan()
  const [params, setParams] = useSearchParams()
  const view = params.get('view') ?? 'mine'
  const status = params.get('status') ?? 'open'
  const q = params.get('q') ?? ''
  const page = Math.max(0, Number(params.get('page') ?? '0') || 0)
  const [editing, setEditing] = useState<TaskView | 'new' | null>(null)
  const canManage = can(PERMISSIONS.taskManage)

  const tasks = useQuery({
    queryKey: ['tasks', { view, status, q, page }],
    queryFn: () =>
      api.get<Page<TaskView>>(
        `/tasks?${toQuery({ assignee: VIEWS[view] ?? 'me', status: STATUSES[status] ?? '', q, page, size: SIZE })}`,
      ),
    placeholderData: keepPreviousData,
    refetchOnMount: 'always',
  })

  function update(next: Record<string, string>) {
    const merged = new URLSearchParams(params)
    for (const [key, value] of Object.entries(next)) {
      if (value) merged.set(key, value)
      else merged.delete(key)
    }
    setParams(merged, { replace: true })
  }

  function onSearch(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const value = new FormData(event.currentTarget).get('q')
    update({ q: typeof value === 'string' ? value.trim() : '', page: '' })
  }

  const today = todayIso()

  return (
    <>
      <PageHeader
        title="Tasks"
        description="Follow-ups across people, organizations and products."
        actions={canManage && <Button onClick={() => setEditing('new')}>New task</Button>}
      />
      <div className="mb-4 flex flex-wrap items-end gap-3">
        <div className="space-y-1.5">
          <Label htmlFor="tasks-view">Show</Label>
          <NativeSelect id="tasks-view" value={view} onChange={(e) => update({ view: e.target.value, page: '' })}>
            <option value="mine">My tasks</option>
            <option value="all">All tasks</option>
            <option value="unassigned">Unassigned</option>
          </NativeSelect>
        </div>
        <div className="space-y-1.5">
          <Label htmlFor="tasks-status">Status</Label>
          <NativeSelect
            id="tasks-status"
            value={status}
            onChange={(e) => update({ status: e.target.value, page: '' })}
          >
            <option value="open">Open</option>
            <option value="done">Done</option>
            <option value="cancelled">Cancelled</option>
            <option value="any">Any status</option>
          </NativeSelect>
        </div>
        <form onSubmit={onSearch} className="flex items-end gap-2" role="search">
          <div className="space-y-1.5">
            <Label htmlFor="tasks-q">Search tasks</Label>
            <Input id="tasks-q" name="q" defaultValue={q} placeholder="Title" />
          </div>
          <Button type="submit" variant="outline">
            Search
          </Button>
        </form>
      </div>

      {tasks.isPending ? (
        <ListSkeleton />
      ) : tasks.isError ? (
        <ErrorState error={tasks.error} onRetry={() => void tasks.refetch()} />
      ) : tasks.data.items.length === 0 ? (
        <EmptyState
          title="No tasks here."
          description="Try another view or status."
          action={
            page > 0 && (
              <Button variant="outline" onClick={() => update({ page: '' })}>
                Back to first page
              </Button>
            )
          }
        />
      ) : (
        <>
          <div className="overflow-x-auto rounded-lg border">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Task</TableHead>
                  <TableHead>Record</TableHead>
                  <TableHead>Assignee</TableHead>
                  <TableHead>Due</TableHead>
                  <TableHead>Priority</TableHead>
                  <TableHead>Status</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {tasks.data.items.map((task) => {
                  const overdue = task.dueOn !== null && task.dueOn < today && isOpen(task.status)
                  return (
                    <TableRow key={task.id}>
                      <TableCell className="font-medium">
                        {canManage ? (
                          <button
                            type="button"
                            className="text-left underline-offset-4 hover:underline"
                            aria-label={`Edit ${task.title}`}
                            onClick={() => setEditing(task)}
                          >
                            {task.title}
                          </button>
                        ) : (
                          task.title
                        )}
                      </TableCell>
                      <TableCell>
                        <SubjectLink subject={task.subject} />
                      </TableCell>
                      <TableCell>{task.assignee?.name ?? 'Unassigned'}</TableCell>
                      <TableCell className={overdue ? 'text-destructive' : undefined}>
                        {formatDate(task.dueOn)}
                        {overdue && <span className="ml-1 text-xs font-medium">Overdue</span>}
                      </TableCell>
                      <TableCell>
                        <Badge variant={task.priority === 'URGENT' || task.priority === 'HIGH' ? 'default' : 'outline'}>
                          {PRIORITY_LABELS[task.priority]}
                        </Badge>
                      </TableCell>
                      <TableCell>
                        <TaskStatusSelect task={task} />
                      </TableCell>
                    </TableRow>
                  )
                })}
              </TableBody>
            </Table>
          </div>
          <Pagination
            page={tasks.data.page}
            size={tasks.data.size}
            total={tasks.data.total}
            onPage={(p) => update({ page: String(p) })}
          />
        </>
      )}
      {editing && (
        <TaskDialog
          key={editing === 'new' ? 'new' : editing.id}
          task={editing === 'new' ? undefined : editing}
          onClose={() => setEditing(null)}
        />
      )}
    </>
  )
}
