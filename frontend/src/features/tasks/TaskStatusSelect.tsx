import { useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { toast } from 'sonner'
import { NativeSelect } from '@/components/form/NativeSelect'
import { PERMISSIONS, useCan } from '@/features/auth/permissions'
import { useTenantSession } from '@/features/auth/tenantSession'
import { useApi } from '@/lib/api/ApiContext'
import { problemMessage } from '@/lib/api/problems'
import type { TaskStatus, TaskView } from '@/lib/api/types'
import { STATUS_LABELS } from './labels'

/** Managers can move any task; an assignee can move their own (server TaskService.changeStatus). */
export function TaskStatusSelect({ task }: { task: TaskView }) {
  const api = useApi()
  const can = useCan()
  const session = useTenantSession()
  const queryClient = useQueryClient()
  const [busy, setBusy] = useState(false)
  const me = session.state.status === 'authenticated' ? session.state.profile.user.id : null
  const allowed = can(PERMISSIONS.taskManage) || (me !== null && task.assignee?.id === me)

  async function change(status: TaskStatus) {
    setBusy(true)
    try {
      await api.post<TaskView>(`/tasks/${task.id}/status`, { status })
      toast.success(`Marked ${STATUS_LABELS[status].toLowerCase()}.`)
      await queryClient.invalidateQueries({ queryKey: ['tasks'] })
    } catch (error) {
      toast.error(problemMessage(error))
    } finally {
      setBusy(false)
    }
  }

  return (
    <NativeSelect
      aria-label={`Status of ${task.title}`}
      value={task.status}
      disabled={!allowed || busy}
      onChange={(event) => void change(event.target.value as TaskStatus)}
    >
      {Object.entries(STATUS_LABELS).map(([value, label]) => (
        <option key={value} value={value}>
          {label}
        </option>
      ))}
    </NativeSelect>
  )
}
