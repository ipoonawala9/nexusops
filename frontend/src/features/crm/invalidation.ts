import type { QueryClient } from '@tanstack/react-query'

/** Customer and dashboard figures derive from deals and leads, so any change to those refreshes them. */
export async function invalidateCrmFigures(queryClient: QueryClient) {
  await Promise.all([
    queryClient.invalidateQueries({ queryKey: ['crm-customer'] }),
    queryClient.invalidateQueries({ queryKey: ['crm-customers'] }),
    queryClient.invalidateQueries({ queryKey: ['crm-dashboard'] }),
  ])
}
