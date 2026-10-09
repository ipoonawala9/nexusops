import type { QueryClient } from '@tanstack/react-query'

/** Tickets, their SLA, context and the dashboard depend on each other: any HelpDesk write refreshes them all. */
export async function invalidateHelpDesk(queryClient: QueryClient): Promise<void> {
  await queryClient.invalidateQueries({ queryKey: ['helpdesk'] })
}
