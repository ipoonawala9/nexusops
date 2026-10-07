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

export interface RequestOptions {
  /** Never try a token refresh for this request (auth endpoints, including refresh itself). */
  skipAuthRefresh?: boolean
}

export interface ApiClient {
  request<T>(path: string, init?: RequestInit, options?: RequestOptions): Promise<T>
  get<T>(path: string, options?: RequestOptions): Promise<T>
  post<T>(path: string, body?: unknown, options?: RequestOptions): Promise<T>
  put<T>(path: string, body?: unknown, options?: RequestOptions): Promise<T>
  patch<T>(path: string, body?: unknown, options?: RequestOptions): Promise<T>
  del<T>(path: string, options?: RequestOptions): Promise<T>
  /** multipart/form-data POST; the browser sets the boundary. */
  upload<T>(path: string, form: FormData, options?: RequestOptions): Promise<T>
  /** A binary response (e.g. a document) with the file name from Content-Disposition. */
  download(path: string, options?: RequestOptions): Promise<DownloadedFile>
}

export interface DownloadedFile {
  blob: Blob
  fileName: string
}

const JSON_ACCEPT = 'application/json, application/problem+json'

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

  function send(
    path: string,
    init: RequestInit,
    token: string | null,
    accept: string,
  ): Promise<Response> {
    const headers = new Headers(init.headers)
    if (token) headers.set('Authorization', `Bearer ${token}`)
    if (init.body !== undefined && !(init.body instanceof FormData) && !headers.has('Content-Type')) {
      headers.set('Content-Type', 'application/json')
    }
    headers.set('Accept', accept)
    return doFetch(`${options.baseUrl}${path}`, { ...init, headers, credentials: 'include' })
  }

  async function authorized(
    path: string,
    init: RequestInit,
    requestOptions: RequestOptions,
    accept: string,
  ): Promise<Response> {
    const sentToken = options.tokens.get()
    let response = await send(path, init, sentToken, accept)
    // Only an expired/invalid *sent* token is worth refreshing; anonymous 401s (bad login) are final.
    if (response.status === 401 && sentToken && !requestOptions.skipAuthRefresh) {
      const current = options.tokens.get()
      // Another request may already have refreshed while this one was in flight.
      const newToken = current && current !== sentToken ? current : await refreshOnce()
      if (newToken) {
        options.tokens.set(newToken)
        response = await send(path, init, newToken, accept)
      } else {
        options.tokens.set(null)
        options.onAuthFailure?.()
      }
    }
    if (!response.ok) throw new ApiError(await toProblem(response))
    return response
  }

  async function request<T>(
    path: string,
    init: RequestInit = {},
    requestOptions: RequestOptions = {},
  ): Promise<T> {
    const response = await authorized(path, init, requestOptions, JSON_ACCEPT)
    if (response.status === 204) return undefined as T
    // Bodiless successes other than 204 exist too (e.g. 202 Accepted from resend-verification).
    const text = await response.text()
    return (text ? JSON.parse(text) : undefined) as T
  }

  const withBody =
    (method: string) =>
    <T>(path: string, body?: unknown, requestOptions?: RequestOptions) =>
      request<T>(
        path,
        { method, body: body === undefined ? undefined : JSON.stringify(body) },
        requestOptions,
      )

  return {
    request,
    get: <T>(path: string, requestOptions?: RequestOptions) => request<T>(path, {}, requestOptions),
    post: withBody('POST'),
    put: withBody('PUT'),
    patch: withBody('PATCH'),
    del: <T>(path: string, requestOptions?: RequestOptions) =>
      request<T>(path, { method: 'DELETE' }, requestOptions),
    upload: <T>(path: string, form: FormData, requestOptions?: RequestOptions) =>
      request<T>(path, { method: 'POST', body: form }, requestOptions),
    download: async (path: string, requestOptions: RequestOptions = {}) => {
      const response = await authorized(path, {}, requestOptions, '*/*')
      return {
        blob: await response.blob(),
        fileName: fileNameFrom(response.headers.get('Content-Disposition')),
      }
    },
  }
}

/** The download name from Content-Disposition; RFC 5987 `filename*` wins over `filename`. */
export function fileNameFrom(disposition: string | null): string {
  if (disposition) {
    const encoded = /filename\*=(?:UTF-8|utf-8)''([^;]+)/.exec(disposition)
    if (encoded) {
      try {
        return decodeURIComponent(encoded[1].trim())
      } catch {
        // fall back to the plain parameter
      }
    }
    const plain = /filename="([^"]*)"/.exec(disposition) ?? /filename=([^;]+)/.exec(disposition)
    if (plain) return plain[1].trim()
  }
  return 'download'
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
