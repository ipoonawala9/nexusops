import type { TaskPriority, TaskStatus } from '@/lib/api/types'

export const STATUS_LABELS: Record<TaskStatus, string> = {
  OPEN: 'Open',
  IN_PROGRESS: 'In progress',
  DONE: 'Done',
  CANCELLED: 'Cancelled',
}

export const PRIORITY_LABELS: Record<TaskPriority, string> = {
  LOW: 'Low',
  NORMAL: 'Normal',
  HIGH: 'High',
  URGENT: 'Urgent',
}

export function isOpen(status: TaskStatus): boolean {
  return status === 'OPEN' || status === 'IN_PROGRESS'
}
