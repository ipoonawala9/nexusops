/**
 * Runs a follow-up refresh after a mutation already succeeded (reload the profile, refetch a list). Its failure must
 * not be reported as if the mutation failed, so it is logged and swallowed; the next navigation refetches anyway.
 */
export async function quietly(task: () => Promise<unknown>): Promise<void> {
  try {
    await task()
  } catch (error) {
    console.warn('Refresh after a successful change failed', error)
  }
}
