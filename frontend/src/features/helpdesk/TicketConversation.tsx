import { cn } from '@/lib/utils'
import type { MessageView } from '@/lib/api/types'
import { formatDateTime } from '@/lib/format'
import { MESSAGE_KIND_LABELS } from './labels'

/** The ticket's messages, oldest first. Internal notes look different from what the customer saw. */
export function TicketConversation({ messages }: { messages: MessageView[] }) {
  if (messages.length === 0)
    return <p className="text-sm text-muted-foreground">No messages yet.</p>
  return (
    <ol className="space-y-3">
      {messages.map((m) => (
        <li
          key={m.id}
          className={cn(
            'rounded-lg border p-3 text-sm',
            m.kind === 'INTERNAL_NOTE' && 'border-dashed bg-muted/50',
          )}
        >
          <div className="mb-1 flex flex-wrap items-baseline gap-2">
            <span className="font-medium">{MESSAGE_KIND_LABELS[m.kind]}</span>
            <span className="text-muted-foreground">
              {m.author?.name ?? 'Former member'} · {formatDateTime(m.createdAt)}
            </span>
          </div>
          <p className="whitespace-pre-wrap">{m.body}</p>
          {m.kind === 'PUBLIC_REPLY' && (
            <p className="mt-2 text-xs text-muted-foreground">
              {m.emailedTo
                ? `Emailed to ${m.emailedTo}`
                : 'Not emailed: the requester has no email address.'}
            </p>
          )}
        </li>
      ))}
    </ol>
  )
}
