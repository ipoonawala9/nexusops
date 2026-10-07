import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { ErrorState, ListSkeleton } from '@/components/states'
import { SubjectLink } from '@/features/records/SubjectLink'
import { useApi } from '@/lib/api/ApiContext'
import type { Page, TaskView } from '@/lib/api/types'
import { formatDate } from '@/lib/format'
import { toQuery } from '@/lib/query'

export function MyTasksCard() {
  const api = useApi()
  const tasks = useQuery({
    queryKey: ['tasks', { view: 'mine', status: 'open', overview: true }],
    queryFn: () =>
      api.get<Page<TaskView>>(
        `/tasks?${toQuery({ assignee: 'me', status: 'OPEN,IN_PROGRESS', size: 5 })}`,
      ),
  })
  return (
    <Card role="region" aria-label="My open tasks">
      <CardHeader>
        <CardTitle>My open tasks</CardTitle>
      </CardHeader>
      <CardContent className="space-y-2 text-sm">
        {tasks.isPending ? (
          <ListSkeleton rows={2} />
        ) : tasks.isError ? (
          <ErrorState error={tasks.error} onRetry={() => void tasks.refetch()} />
        ) : tasks.data.items.length === 0 ? (
          <p className="text-muted-foreground">Nothing assigned to you.</p>
        ) : (
          <ul className="space-y-1">
            {tasks.data.items.map((task) => (
              <li key={task.id}>
                <span className="font-medium">{task.title}</span>{' '}
                <span className="text-muted-foreground">
                  · due {formatDate(task.dueOn)}
                  {task.subject && ' · '}
                </span>
                {task.subject && <SubjectLink subject={task.subject} />}
              </li>
            ))}
          </ul>
        )}
        <Link to="/app/tasks" className="underline">
          All tasks
        </Link>
      </CardContent>
    </Card>
  )
}
