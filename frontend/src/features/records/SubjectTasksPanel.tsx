import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Button } from '@/components/ui/button'
import { ErrorState, ListSkeleton } from '@/components/states'
import { PERMISSIONS, useCan } from '@/features/auth/permissions'
import { TaskDialog } from '@/features/tasks/TaskDialog'
import { TaskStatusSelect } from '@/features/tasks/TaskStatusSelect'
import { useApi } from '@/lib/api/ApiContext'
import type { Page, SubjectType, TaskView } from '@/lib/api/types'
import { formatDate } from '@/lib/format'
import { toQuery } from '@/lib/query'

export function SubjectTasksPanel({
  subjectType,
  subjectId,
  label,
  archived,
}: {
  subjectType: SubjectType
  subjectId: string
  label: string
  archived: boolean
}) {
  const api = useApi()
  const can = useCan()
  const [editing, setEditing] = useState<TaskView | 'new' | null>(null)
  const canRead = can(PERMISSIONS.taskRead)
  const canManage = can(PERMISSIONS.taskManage)
  const tasks = useQuery({
    queryKey: ['tasks', { subjectType, subjectId }],
    queryFn: () =>
      api.get<Page<TaskView>>(`/tasks?${toQuery({ subjectType, subjectId, size: 50 })}`),
    enabled: canRead,
  })
  if (!canRead) return null

  return (
    <section aria-label="Tasks" className="space-y-3">
      <div className="flex items-center justify-between">
        <h2 className="text-lg font-semibold">Tasks</h2>
        {canManage && !archived && (
          <Button size="sm" variant="outline" onClick={() => setEditing('new')}>
            New task
          </Button>
        )}
      </div>
      {tasks.isPending ? (
        <ListSkeleton rows={2} />
      ) : tasks.isError ? (
        <ErrorState error={tasks.error} onRetry={() => void tasks.refetch()} />
      ) : tasks.data.items.length === 0 ? (
        <p className="text-sm text-muted-foreground">No tasks for this record.</p>
      ) : (
        <ul className="divide-y rounded-lg border">
          {tasks.data.items.map((task) => (
            <li
              key={task.id}
              className="flex flex-wrap items-center justify-between gap-2 p-3 text-sm"
            >
              <div>
                {canManage ? (
                  <button
                    type="button"
                    className="font-medium underline-offset-4 hover:underline"
                    aria-label={`Edit ${task.title}`}
                    onClick={() => setEditing(task)}
                  >
                    {task.title}
                  </button>
                ) : (
                  <span className="font-medium">{task.title}</span>
                )}
                <p className="text-xs text-muted-foreground">
                  {task.assignee?.name ?? 'Unassigned'} · due {formatDate(task.dueOn)}
                </p>
              </div>
              <div className="w-40">
                <TaskStatusSelect task={task} />
              </div>
            </li>
          ))}
        </ul>
      )}
      {editing && (
        <TaskDialog
          key={editing === 'new' ? 'new' : editing.id}
          task={editing === 'new' ? undefined : editing}
          subject={editing === 'new' ? { type: subjectType, id: subjectId, label } : undefined}
          onClose={() => setEditing(null)}
        />
      )}
    </section>
  )
}
