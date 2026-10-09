import { useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { toast } from 'sonner'
import { useApi } from '@/lib/api/ApiContext'
import { problemMessage } from '@/lib/api/problems'
import type { TicketView } from '@/lib/api/types'
import { invalidateHelpDesk } from './invalidation'

/** Posts a ticket state change. On success it stores the returned ticket; on failure it explains and reloads the ticket. */
export function useTicketAction(ticketId: string) {
  const api = useApi()
  const queryClient = useQueryClient()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const key = ['helpdesk', 'ticket', ticketId]

  async function run(path: string, body: unknown, success: string): Promise<boolean> {
    setBusy(true)
    setError(null)
    try {
      const updated = await api.post<TicketView>(path, body)
      queryClient.setQueryData(key, updated)
      await invalidateHelpDesk(queryClient)
      toast.success(success)
      return true
    } catch (e) {
      setError(problemMessage(e))
      // the version may have moved: show the current ticket
      void queryClient.invalidateQueries({ queryKey: key })
      return false
    } finally {
      setBusy(false)
    }
  }

  return { busy, error, setError, run }
}
