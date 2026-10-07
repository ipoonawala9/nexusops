/** A route table behind fetchImpl: the real ApiClient runs against it. Routes: 'METHOD /path/:param'. */
export interface FakeRequest {
  route: string
  method: string
  path: string
  query: URLSearchParams
  params: Record<string, string>
  body: unknown
  headers: Headers
  form?: FormData
}

export interface FakeReply {
  status?: number
  body?: unknown
  /** A raw (non-JSON) body, e.g. a file download. */
  raw?: BodyInit
  headers?: Record<string, string>
}

export type FakeHandler = FakeReply | ((request: FakeRequest) => FakeReply)

export interface FakeServer {
  fetchImpl: typeof fetch
  calls: FakeRequest[]
  on(route: string, handler: FakeHandler): FakeServer
  callsTo(route: string): FakeRequest[]
}

export function fakeServer(routes: Record<string, FakeHandler> = {}): FakeServer {
  const table = new Map<string, FakeHandler>(Object.entries(routes))
  const calls: FakeRequest[] = []

  const fetchImpl: typeof fetch = async (input, init) => {
    const url = new URL(String(input), 'http://localhost')
    const method = (init?.method ?? 'GET').toUpperCase()
    const path = url.pathname.replace(/^\/api\/v1/, '')
    const body = typeof init?.body === 'string' ? (JSON.parse(init.body) as unknown) : undefined
    // Later registrations win, so tests can override a default route.
    for (const [route, handler] of [...table.entries()].reverse()) {
      const params = match(route, method, path)
      if (!params) continue
      const request: FakeRequest = {
        route,
        method,
        path,
        query: url.searchParams,
        params,
        body,
        headers: new Headers(init?.headers),
        form: init?.body instanceof FormData ? init.body : undefined,
      }
      calls.push(request)
      return toResponse(typeof handler === 'function' ? handler(request) : handler)
    }
    throw new Error(`fakeServer: no route for ${method} ${path}`)
  }

  const server: FakeServer = {
    fetchImpl,
    calls,
    on(route, handler) {
      table.delete(route)
      table.set(route, handler)
      return server
    },
    callsTo: (route) => calls.filter((call) => call.route === route),
  }
  return server
}

function match(route: string, method: string, path: string): Record<string, string> | null {
  const [routeMethod, pattern] = route.split(' ')
  if (routeMethod !== method || !pattern) return null
  const expected = pattern.split('/')
  const actual = path.split('/')
  if (expected.length !== actual.length) return null
  const params: Record<string, string> = {}
  for (let i = 0; i < expected.length; i++) {
    const segment = expected[i]
    if (segment.startsWith(':')) params[segment.slice(1)] = decodeURIComponent(actual[i])
    else if (segment !== actual[i]) return null
  }
  return params
}

function toResponse({ status = 200, body, raw, headers }: FakeReply): Response {
  if (raw !== undefined) return new Response(raw, { status, headers })
  if (body === undefined) return new Response(null, { status: status === 200 ? 204 : status })
  const isProblem = status >= 400
  const payload =
    isProblem && typeof body === 'object' && body !== null ? { status, ...body } : body
  return new Response(JSON.stringify(payload), {
    status,
    headers: { 'Content-Type': isProblem ? 'application/problem+json' : 'application/json' },
  })
}
