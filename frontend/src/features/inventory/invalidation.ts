import type { QueryClient } from '@tanstack/react-query'

/** Stock, orders and suggestions all depend on each other: any Inventory write refreshes every Inventory query. */
export async function invalidateInventory(queryClient: QueryClient): Promise<void> {
  await queryClient.invalidateQueries({ queryKey: ['inventory'] })
}
