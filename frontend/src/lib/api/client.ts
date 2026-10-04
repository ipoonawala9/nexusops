import type { TokenStore } from './tokenStore'

export interface ProblemDetail {
  type?: string
  title?: string
  status: number
  detail?: string
  instance?: string
  requestId?: string
  errors?: Array<{ field: string; message: string }>
}

export class ApiError extends Error {
  readonly problem: ProblemDetail

  constructor(problem: ProblemDetail) {
    super(problem.detail ?? problem.title ?? `Request failed with status ${problem.status}`)
    this.name = 'ApiError'
    this.problem = problem
  }

  get status(): number {
    return this.problem.status
  }
}

export interface ApiClientOptions {
  baseUrl: string
  tokens: TokenStore
  /** Obtains a new access token (e.g. via the refresh cookie). Resolve null when refresh is impossible. */
  refresh: () => Promise<string | null>
  onAuthFailure?: () => void
  fetchImpl?: typeof fetch
}

export interface ApiClient {
  request<T>(path: string, init?: RequestInit): Promise<T>
  get<T>(path: string): Promise<T>
  post<T>(path: string, body?: unknown): Promise<T>
  put<T>(path: string, body?: unknown): Promise<T>
  patch<T>(path: string, body?: unknown): Promise<T>
  del<T>(path: string): Promise<T>
}

export function createApiClient(options: ApiClientOptions): ApiClient {
  const doFetch: typeof fetch = (input, init) => (options.fetchImpl ?? fetch)(input, init)
  let inflightRefresh: Promise<string | null> | null = null

  function refreshOnce(): Promise<string | null> {
    if (!inflightRefresh) {
      inflightRefresh = options
        .refresh()
        .catch(() => null)
        .finally(() => {
          inflightRefresh = null
        })
    }
    return inflightRefresh
  }

  function send(path: string, init: RequestInit, token: string | null): Promise<Response> {
    const headers = new Headers(init.headers)
    if (token) headers.set('Authorization', `Bearer ${token}`)
    if (init.body !== undefined && !headers.has('Content-Type')) {
      headers.set('Content-Type', 'application/json')
    }
    headers.set('Accept', 'application/json, application/problem+json')
    return doFetch(`${options.baseUrl}${path}`, { ...init, headers, credentials: 'include' })
  }

  async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
    let response = await send(path, init, options.tokens.get())
    if (response.status === 401) {
      const newToken = await refreshOnce()
      if (newToken) {
        options.tokens.set(newToken)
        response = await send(path, init, newToken)
      } else {
        options.tokens.set(null)
        options.onAuthFailure?.()
      }
    }
    if (!response.ok) throw new ApiError(await toProblem(response))
    if (response.status === 204) return undefined as T
    return (await response.json()) as T
  }

  const withBody =
    (method: string) =>
    <T>(path: string, body?: unknown) =>
      request<T>(path, { method, body: body === undefined ? undefined : JSON.stringify(body) })

  return {
    request,
    get: <T>(path: string) => request<T>(path),
    post: withBody('POST'),
    put: withBody('PUT'),
    patch: withBody('PATCH'),
    del: <T>(path: string) => request<T>(path, { method: 'DELETE' }),
  }
}

async function toProblem(response: Response): Promise<ProblemDetail> {
  const contentType = response.headers.get('Content-Type') ?? ''
  if (contentType.includes('json')) {
    try {
      const body = (await response.json()) as Partial<ProblemDetail>
      return { ...body, status: response.status }
    } catch {
      // fall through to the generic problem below
    }
  }
  return { status: response.status, title: response.statusText || 'Request failed' }
}
