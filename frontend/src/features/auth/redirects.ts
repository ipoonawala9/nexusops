/** WHATWG URL parsing treats `..`, `.%2e`, `%2e.` and `%2e%2e` (any case) as a parent-directory segment. */
function isDotDot(segment: string): boolean {
  return segment.toLowerCase().replace(/%2e/g, '.') === '..'
}

/**
 * Only same-app paths under `prefix` (on a path boundary, without `..` segments) are valid post-login targets, so
 * there is no open redirect and no escaping the prefix. Anything else gives `fallback` (default: the prefix).
 */
export function safeNext(next: string | null, prefix: string, fallback: string = prefix): string {
  if (!next || next.startsWith('//')) return fallback
  const underPrefix =
    next === prefix || next.startsWith(`${prefix}/`) || next.startsWith(`${prefix}?`)
  const path = next.split(/[?#]/, 1)[0]
  if (!underPrefix || path.split('/').some(isDotDot)) return fallback
  return next
}

/** Where a guard sends an anonymous visitor: a deliberate sign-out gets a plain sign-in page, anything else `?next=`. */
export function signInPath(
  loginPath: string,
  state: { reason?: string },
  location: { pathname: string; search: string },
): string {
  if (state.reason === 'signed-out') return loginPath
  return `${loginPath}?next=${encodeURIComponent(location.pathname + location.search)}`
}
