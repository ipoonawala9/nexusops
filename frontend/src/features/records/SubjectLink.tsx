import { Link } from 'react-router'
import type { SubjectRef } from '@/lib/api/types'

/** Where a subject record's page lives (server SubjectResolver types). */
export function subjectPath(type: string, id: string): string | null {
  if (type === 'PARTY') return `/app/directory/${id}`
  if (type === 'PRODUCT') return `/app/products/${id}`
  return null
}

export function SubjectLink({ subject }: { subject: SubjectRef | null }) {
  if (!subject) return <span className="text-muted-foreground">—</span>
  if (!subject.label) return <span className="text-muted-foreground">Restricted record</span>
  const path = subjectPath(subject.type, subject.id)
  return path ? (
    <Link to={path} className="underline-offset-4 hover:underline">
      {subject.label}
    </Link>
  ) : (
    <span>{subject.label}</span>
  )
}
