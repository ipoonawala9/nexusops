/** Access tokens live only in memory (never localStorage) — see ADR-0003. */
export interface TokenStore {
  get(): string | null
  set(token: string | null): void
}

export function createMemoryTokenStore(): TokenStore {
  let token: string | null = null
  return {
    get: () => token,
    set: (next) => {
      token = next
    },
  }
}
