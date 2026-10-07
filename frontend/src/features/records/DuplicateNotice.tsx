import { Link } from 'react-router'
import { describedBy, Field } from '@/components/form/Field'
import { Textarea } from '@/components/ui/textarea'
import type { DuplicateCandidate } from '@/lib/api/types'

export function DuplicateNotice({
  candidates,
  reason,
  onReason,
  reasonError,
  onOpen,
}: {
  candidates: DuplicateCandidate[]
  reason: string
  onReason: (value: string) => void
  reasonError?: string
  /** Called when the user follows a link to an existing record (e.g. to close the dialog). */
  onOpen: () => void
}) {
  const id = 'field-duplicateReason'
  return (
    <div className="space-y-3 rounded-md border border-amber-300 bg-amber-50 p-3 text-sm dark:border-amber-800 dark:bg-amber-950/30">
      <div>
        <p className="font-medium">This looks like a record that already exists.</p>
        <ul className="mt-1 space-y-1">
          {candidates.map((candidate) => (
            <li key={candidate.id}>
              <Link to={`/app/directory/${candidate.id}`} className="underline" onClick={onOpen}>
                {candidate.name}
              </Link>{' '}
              <span className="text-muted-foreground">
                {[candidate.email ?? candidate.domain, candidate.archived ? 'archived' : null]
                  .filter(Boolean)
                  .join(' · ')}
              </span>
            </li>
          ))}
        </ul>
      </div>
      <Field id={id} label="Why keep a separate record?" error={reasonError}>
        <Textarea
          id={id}
          rows={2}
          maxLength={500}
          value={reason}
          onChange={(event) => onReason(event.target.value)}
          aria-invalid={reasonError ? true : undefined}
          aria-describedby={describedBy(id, reasonError)}
        />
      </Field>
    </div>
  )
}
