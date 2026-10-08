import { useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { toast } from 'sonner'
import { useApi } from '@/lib/api/ApiContext'
import { problemMessage } from '@/lib/api/problems'
import { invalidateInventory } from './invalidation'
import { shortageText } from './shortages'

/** Posts an order state change; on success stores the returned order, on failure explains and reloads it. */
export function useOrderAction(queryKey: readonly unknown[]) {
  const api = useApi()
  const queryClient = useQueryClient()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function run(path: string, body: unknown, success: string): Promise<boolean> {
    setBusy(true)
    setError(null)
    try {
      const updated = await api.post<unknown>(path, body)
      queryClient.setQueryData(queryKey, updated)
      await invalidateInventory(queryClient)
      toast.success(success)
      return true
    } catch (e) {
      setError(shortageText(e) ?? problemMessage(e))
      // the version or the stock may have moved: show the current order
      void queryClient.invalidateQueries({ queryKey })
      return false
    } finally {
      setBusy(false)
    }
  }

  return { busy, error, setError, run }
}
