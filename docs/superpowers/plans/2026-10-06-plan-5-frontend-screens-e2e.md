# Plan 5 — Frontend Screens and End-to-End Journeys: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the frontend skeleton into the product's first usable UI, closing blueprint Phases 1–3 (spec §11). It covers:
- public sign-up, email verification, sign-in and invitation acceptance;
- the workspace shell, with nav filtered by enabled modules and permissions;
- Settings: Workspace, Users and invitations, Roles with a permission matrix, Modules;
- the Audit log;
- the platform console: staff sign-in with TOTP, and the workspace list with suspend/reactivate;
- Playwright journeys that drive the real stack end to end.

**Architecture:**
- React 19, React Router 7 (data router), TanStack Query, React Hook Form + Zod, Tailwind 4 and shadcn (base-nova, Base UI).
- Two independent sessions, *tenant* and *platform*, each with:
  - an in-memory access token (ADR-0003);
  - a cookie-based silent refresh;
  - its own `ApiClient`, built on the existing `createApiClient`.
- Each route subtree is wrapped in its own `ApiProvider`, so a page simply calls `useApi()`.
- Everything the UI hides is enforced by the server anyway. The UI checks are UX only.

**Tech Stack:** Vite 8, TypeScript 6 (strict), Vitest 5 + Testing Library + user-event, Playwright 1.63, oxlint, Prettier.

**Spec:** `docs/superpowers/specs/2026-10-04-platform-foundation-design.md` §2 (UI row), §6 (flows), §10 (API), §11 (frontend), §13 (E2E), §16–§17. API contract: `docs/api/openapi.json`. Builds on `main` at `0137709` (Plans 1–4 merged).

## Scope

**In:**
- Session plumbing for tenant and platform.
- Form and problem-detail helpers.
- Shared UI states: loading, error, empty.
- Every screen listed in spec §11.
- Module and feature placeholders that name their blueprint phase.
- Platform console.
- Playwright journeys against the Docker Compose stack, and a CI E2E job on that stack.
- README.

**Out:**
- Business modules (CRM, Inventory, HelpDesk, HRMS: Phases 5–8).
- Dashboards with real data (Phase 11).
- Dark-mode toggle.
- i18n.
- Platform audit viewer.
- Managing platform staff accounts over HTTP (CLI only, ADR-0007).

## Decisions taken while writing this plan (flagged for review)

1. **The UI kit stays on shadcn base-nova.** Task 1 adds the components with `npx shadcn@latest add …`. Pages use only these:
   - `Button`, `Card`, `Input`, `Label`, `Textarea`, `Badge`, `Skeleton`, `Table*`, `Dialog*`, `Alert*`, `Separator`, and `Toaster`/`toast` from sonner.
   - Selects, checkboxes and switches are small native-element wrappers in `src/components/form/`. Native controls are accessible, keyboard-friendly and testable in jsdom without portal or focus-trap surprises.
2. **There are two sessions and they never share tokens.**
   - Tenant: refresh `POST /auth/refresh`, profile `GET /me`.
   - Platform: refresh `POST /platform/auth/refresh`, profile `GET /platform/me`.
   - Each session restores once at boot. The restore is memoized per session, so React StrictMode's double mount can't fire two refresh rotations that race each other (ADR-0003 grace window).
   - An unrecoverable 401 clears the session and the query cache, then sends the user to the right sign-in page with `?next=` (only `/app…` and `/platform…` targets are honoured, so there is no open redirect).
3. **Routes:**
   - Public: `/`, `/signup`, `/verify-email`, `/login`, `/invite/accept`.
   - App: `/app` (Overview); `/app/crm`, `/app/inventory`, `/app/helpdesk`, `/app/hrms`, `/app/workflows`, `/app/insights`, `/app/assistant`; `/app/audit`; `/app/settings/workspace`, `/app/settings/users`, `/app/settings/roles`, `/app/settings/modules`.
   - Platform: `/platform/login`, `/platform/tenants`.

   `/` sends signed-in users to `/app` and everyone else to `/login`. The links in the verification and invitation emails (`/verify-email?token=`, `/invite/accept?token=`) are unchanged.
4. **Placeholders name their blueprint phase:**
   - CRM: Phase 5. Inventory: Phase 6. HelpDesk: Phase 7. HRMS: Phase 8.
   - Workflows: Phase 9. Insights: Phase 11. AI Assistant: Phase 12.
   - Module entries (CRM, Inventory, HelpDesk, HRMS) appear only when the module is enabled for the workspace, per blueprint §23. Workflows, Insights and AI Assistant always appear, as "coming soon".
5. **Permission gating mirrors the server's `@PreAuthorize` exactly.** It shows or hides each section and action, and the matching endpoint returns 403 if the UI is bypassed.

   | UI | Permission |
   |---|---|
   | Audit nav, `/app/audit` | `audit.event.read` |
   | Settings → Workspace (view) | `tenant.settings.read` |
   | Settings → Workspace (save) | `tenant.settings.update` |
   | Settings → Modules (view) | `tenant.settings.read` |
   | Settings → Modules (toggle) | `tenant.modules.manage` |
   | Settings → Users (view, invitation list) | `identity.user.read` |
   | Invite / revoke invitation | `identity.user.invite` |
   | Rename user | `identity.user.update` |
   | Disable / enable user | `identity.user.disable` |
   | Change a user's roles | `authorization.role.assign` (role list needs `authorization.role.read`) |
   | Settings → Roles (view, permission catalog) | `authorization.role.read` |
   | Create / edit / delete roles | `authorization.role.manage` |
   | Platform: workspace list | `platform.tenant.read` |
   | Platform: suspend / reactivate | `platform.tenant.suspend` |
6. **Tokens in URLs.** The verify and accept pages read `token` once, then remove it from the address bar with `history.replaceState`. `index.html` adds `<meta name="referrer" content="no-referrer">`, and nginx sends `Referrer-Policy: no-referrer`. This closes the Plan 3 deferred item "token in GET query".
7. **E2E runs against the real stack.** It uses Docker Compose with `--profile app`: UI on :3000 behind nginx, API, Postgres, Redis and Mailpit.
   - `make e2e` brings the stack up, then runs Playwright with `E2E_BASE_URL=http://localhost:3000`. Playwright starts no web server of its own.
   - Email tokens are read from the Mailpit HTTP API on :8025.
   - The platform journey seeds a known operator, `e2e-ops@nexusops.test`, with idempotent SQL (`e2e/fixtures/platform-operator.sql`). The values are precomputed against the committed, non-secret *local* `PLATFORM_TOTP_KEY` and the Argon2 encoder. Playwright computes TOTP codes with Node's `crypto`.
   - The CI `e2e` job (main and manual runs, as today) uses the same Make target.
8. **Unit-test seam:** page tests run the *real* API client and sessions against `fakeServer()`, a route table behind `fetchImpl`. No module mocking. This exercises the same refresh, problem-detail and permission paths the browser uses.

## Global Constraints

- **Toolchain:**
  - Node 22, npm.
  - Gates: `npm run format:check`, `npm run lint` (oxlint), `npm run typecheck`, `npm test` (Vitest) and `npm run build` must all pass. `make test-frontend` runs them all.
  - TypeScript strict: no `any`, no `@ts-ignore`, no non-null assertions on API data.
  - `@/` is the import alias for `src/`. Prettier formats everything except `src/components/ui/**`, which is shadcn-generated.
- **Commits carry NO AI attribution** of any kind: no `Co-Authored-By` trailer for any AI, and no "Generated with" line. Plain conventional-commit messages only (user requirement).
- **Access tokens live only in memory.** Never use `localStorage`, `sessionStorage` or cookies from JS. Refresh tokens are httpOnly cookies handled by the browser.
- **All API calls go through `useApi()`.** No raw `fetch` in pages. The base URL is `/api/v1`; vite proxies `/api` to :8081, and nginx proxies it to the backend.
- **Server text is shown verbatim.** Use problem `detail` for messages and `errors[]` for field messages, mapped onto the matching form field. When the server sent no detail, use "Something went wrong. Please try again."
- **Every data view has three states:**
  - loading (`Skeleton`);
  - error (`ErrorState`, showing the problem detail and its `requestId`, with a Retry button);
  - empty (`EmptyState`, with a one-line explanation).
- **Accessibility:**
  - Every input has a `<label>`.
  - Errors are linked via `aria-describedby` and announced with `role="alert"`.
  - Every action is reachable by keyboard.
  - Each page has exactly one `<h1>`.
  - Layouts work at 375px wide, and the sidebar collapses into a toggle-able panel on small screens.
- **Exact client-side validation messages** (the server re-validates):
  - required → "Required."
  - email → "Enter a valid email address."
  - password shorter than 12 characters → "Use at least 12 characters."
  - TOTP code → "Enter the 6-digit code."
  - suspend/reactivate reason → "Enter a reason between 1 and 500 characters."
- **Dates** use the browser locale via `Intl.DateTimeFormat`, in medium date + short time style.
- **Branch:** work on `feat/frontend-screens` (already created from `main`); never push or merge without asking.

## Review Focus

1. **The access token expires in the middle of using the app.**
   - Expected: the next request silently refreshes and succeeds, with no visible error.
   - If the refresh cookie is dead too, the user lands on `/login?next=<current page>`, and signing in returns them to that page.

   *Pinned by `session.test.tsx` ("signs out on an unrecoverable 401") and `LoginPage.test.tsx` ("returns to next").*
2. **A user reloads a deep link** such as `/app/settings/users`.
   - Expected: a brief loading state, then the same page, restored from the refresh cookie, with no flash of the sign-in page.

   *Pinned by `guards.test.tsx` ("restores a deep link").*
3. **A user without a permission opens a gated URL directly.**
   - Expected: a "You don't have access to this page" state inside the shell. The nav item is hidden, and no crash happens.
   - When the server answers 403 anyway (because the role changed mid-session), its detail is shown.

   *Pinned by `guards.test.tsx` and `UsersPage.test.tsx` ("shows the server's 403 detail").*
4. **The server rejects a form with field errors**, for example a 409 `slug` "This workspace URL is already taken." on sign-up, or an `email` conflict on invite.
   - Expected: the message appears under that field, focus is not lost, and the rest of the form keeps its values.

   *Pinned by `SignupPage.test.tsx` and `InvitationsPanel.test.tsx`.*
5. **A workspace is suspended while its members are signed in.**
   - Expected: their next action shows "Workspace suspended." and signing in shows the same text. After a platform admin reactivates it, they can work again.

   *Pinned by the E2E `platform.spec.ts`, and by `LoginPage.test.tsx` for the sign-in message.*

---

### Task 1: Session plumbing, API helpers, shared UI states and the test harness

**Files:**
- Run: `npx shadcn@latest add input label textarea badge skeleton table dialog alert separator sonner` (writes `src/components/ui/*`, adds `sonner`/`next-themes`)
- Create: `frontend/src/lib/api/types.ts`
- Create: `frontend/src/lib/api/problems.ts`
- Create: `frontend/src/lib/api/session.ts`
- Create: `frontend/src/lib/api/ApiContext.tsx`
- Create: `frontend/src/lib/session/createSession.tsx`
- Create: `frontend/src/lib/format.ts`
- Create: `frontend/src/features/auth/tenantSession.ts`
- Create: `frontend/src/features/auth/permissions.tsx`
- Create: `frontend/src/features/auth/guards.tsx`
- Create: `frontend/src/app/handles.tsx`
- Create: `frontend/src/app/TenantRoot.tsx`
- Create: `frontend/src/components/states.tsx`
- Create: `frontend/src/components/form/Field.tsx`
- Create: `frontend/src/components/form/NativeSelect.tsx`
- Create: `frontend/src/components/form/Checkbox.tsx`
- Create: `frontend/src/test/fakeServer.ts`
- Create: `frontend/src/test/renderApp.tsx`
- Create: `frontend/src/test/fixtures.ts`
- Modify: `frontend/src/app/App.tsx`, `frontend/src/app/router.tsx`, `frontend/src/app/App.test.tsx`, `frontend/src/test/setup.ts`, `frontend/index.html`, `frontend/nginx.conf`, `frontend/.prettierignore`
- Test: `frontend/src/lib/api/problems.test.ts`, `frontend/src/lib/api/session.test.ts`, `frontend/src/lib/session/session.test.tsx`, `frontend/src/features/auth/guards.test.tsx`

**Interfaces:**
- Produces (later tasks rely on these exact names):
  - `@/lib/api/types`: `Page<T>`, `UserStatus`, `TenantStatus`, `RoleRef`, `UserView`, `TenantSummary`, `Profile`, `TenantSettings`, `ModuleState`, `PermissionView`, `RoleView`, `InvitationStatus`, `InvitationView`, `InvitationPreview`, `AcceptedInvitation`, `AuditEvent`, `TokenResponse`, `PlatformMe`, `PlatformTenant`.
  - `@/lib/api/problems`: `GENERIC_ERROR`, `problemMessage(error, fallback?)`, `applyFieldErrors(error, setError, fields)`.
  - `@/lib/api/session`: `SessionApi`, `createSessionApi({ baseUrl?, refreshPath, fetchImpl? })`.
  - `@/lib/api/ApiContext`: `ApiProvider`, `useApi()`.
  - `@/lib/session/createSession`: `createSession<P, L>(paths, name)` → `{ SessionProvider, useSession }`, plus types `Session<P,L>` and `SessionState<P>`.
  - `@/features/auth/tenantSession`: `TenantSessionProvider`, `useTenantSession()`, `LoginInput`.
  - `@/features/auth/permissions`: `PERMISSIONS`, `ALL_TENANT_PERMISSIONS`, `useCan()` (a `(...codes) => boolean`, true if *any* code is held), `RequirePermission`.
  - `@/features/auth/guards`: `RequireTenantSession`, `RedirectIfTenantSignedIn`, `safeNext(next, prefix)`.
  - `@/app/handles`: `ApiHandles`, `ApiHandlesProvider`, `useApiHandles()`, `createDefaultHandles()`.
  - `@/app/TenantRoot`: `TenantRoot`.
  - `@/components/states`: `FullPageLoading`, `ListSkeleton`, `ErrorState`, `EmptyState`, `NoAccess`, `PageHeader`.
  - `@/components/form/Field`: `Field`.
  - `@/components/form/NativeSelect`: `NativeSelect`.
  - `@/components/form/Checkbox`: `Checkbox`.
  - `@/lib/format`: `formatDateTime`, `fullName`.
  - Test support:
    - `@/test/fakeServer`: `fakeServer(routes)` → `FakeServer`, with `.on(route, handler)` and `.callsTo(route)`.
    - `@/test/renderApp`: `renderApp({ server, path, routes? })` → `{ user, router, server, … }`.
    - `@/test/fixtures`: `testProfile(overrides?)`, `signedIn(server, profile?)`, `signedOut(server)`, `user(overrides?)`.

- [ ] **Step 1: Add the shadcn components and the prettier ignore**

Run inside `frontend/`:

```bash
npx shadcn@latest add input label textarea badge skeleton table dialog alert separator sonner
```

Expected: new files under `src/components/ui/` (`input.tsx`, `label.tsx`, `textarea.tsx`, `badge.tsx`, `skeleton.tsx`, `table.tsx`, `dialog.tsx`, `alert.tsx`, `separator.tsx`, `sonner.tsx`), and `package.json` gains `sonner` (and `next-themes`, which the generated `sonner.tsx` imports).

Create `frontend/.prettierignore` (or append to it, if it exists):

```
src/components/ui/
dist/
test-results/
playwright-report/
```

Then run `npm run format:check && npm run lint && npm run typecheck`. Expected: PASS. If a generated file fails `typecheck`, keep the generator's output and fix only the type error, and note it in the report.

- [ ] **Step 2: Write the failing tests**

`frontend/src/lib/api/problems.test.ts`:

```ts
import { describe, expect, it, vi } from 'vitest'
import { ApiError } from './client'
import { applyFieldErrors, GENERIC_ERROR, problemMessage } from './problems'

describe('problems', () => {
  it('uses the server detail, else a generic message', () => {
    expect(problemMessage(new ApiError({ status: 409, detail: 'Taken.' }))).toBe('Taken.')
    expect(problemMessage(new ApiError({ status: 500 }))).toBe(GENERIC_ERROR)
    expect(problemMessage(new TypeError('Failed to fetch'))).toBe(GENERIC_ERROR)
    expect(problemMessage(new ApiError({ status: 500 }), 'Could not save.')).toBe('Could not save.')
  })

  it('maps server field errors onto known form fields only', () => {
    const setError = vi.fn()
    const error = new ApiError({
      status: 400,
      errors: [
        { field: 'slug', message: 'This workspace URL is already taken.' },
        { field: 'unknown', message: 'ignored' },
      ],
    })
    expect(applyFieldErrors(error, setError, ['slug', 'email'] as const)).toBe(true)
    expect(setError).toHaveBeenCalledTimes(1)
    expect(setError).toHaveBeenCalledWith('slug', {
      type: 'server',
      message: 'This workspace URL is already taken.',
    })
    expect(applyFieldErrors(new ApiError({ status: 401 }), setError, ['slug'] as const)).toBe(false)
  })
})
```

`frontend/src/lib/api/session.test.ts`:

```ts
import { describe, expect, it } from 'vitest'
import { fakeServer } from '@/test/fakeServer'
import { createSessionApi } from './session'

describe('createSessionApi', () => {
  it('restores once even when asked twice (StrictMode double mount)', async () => {
    const server = fakeServer({
      'POST /auth/refresh': { body: { accessToken: 't1', tokenType: 'Bearer', expiresIn: 900 } },
    })
    const api = createSessionApi({ refreshPath: '/auth/refresh', fetchImpl: server.fetchImpl })
    const [a, b] = await Promise.all([api.restore(), api.restore()])
    expect(a && b).toBe(true)
    expect(server.callsTo('POST /auth/refresh')).toHaveLength(1)
    expect(api.tokens.get()).toBe('t1')
  })

  it('reports a failed restore without throwing', async () => {
    const server = fakeServer({ 'POST /auth/refresh': { status: 401, body: { detail: 'expired' } } })
    const api = createSessionApi({ refreshPath: '/auth/refresh', fetchImpl: server.fetchImpl })
    await expect(api.restore()).resolves.toBe(false)
    expect(api.tokens.get()).toBeNull()
  })

  it('refreshes silently when a sent token is rejected', async () => {
    let calls = 0
    const server = fakeServer({
      'POST /auth/refresh': { body: { accessToken: 'fresh', tokenType: 'Bearer', expiresIn: 900 } },
      'GET /me': (req) =>
        ++calls === 1 ? { status: 401, body: {} } : { body: { auth: req.headers.get('Authorization') } },
    })
    const api = createSessionApi({ refreshPath: '/auth/refresh', fetchImpl: server.fetchImpl })
    api.tokens.set('stale')
    await expect(api.client.get('/me')).resolves.toEqual({ auth: 'Bearer fresh' })
  })
})
```

`frontend/src/lib/session/session.test.tsx`:

```tsx
import { screen, waitFor } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { RouteObject } from 'react-router'
import { useTenantSession } from '@/features/auth/tenantSession'
import { TenantRoot } from '@/app/TenantRoot'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, signedOut, testProfile } from '@/test/fixtures'
import { renderApp } from '@/test/renderApp'
import { useApi } from '@/lib/api/ApiContext'

function Probe() {
  const session = useTenantSession()
  const api = useApi()
  return (
    <div>
      <p>status: {session.state.status}</p>
      {session.state.status === 'authenticated' && <p>user: {session.state.profile.user.email}</p>}
      <button
        onClick={() =>
          void session.login({ workspace: 'acme', email: 'ada@acme.test', password: 'pw' })
        }
      >
        login
      </button>
      <button onClick={() => void session.logout()}>logout</button>
      <button onClick={() => void api.get('/users').catch(() => undefined)}>call</button>
    </div>
  )
}

const routes: RouteObject[] = [{ element: <TenantRoot />, children: [{ path: '/', element: <Probe /> }] }]

describe('tenant session', () => {
  it('restores from the refresh cookie at boot', async () => {
    const server = fakeServer()
    signedIn(server)
    renderApp({ server, path: '/', routes })
    expect(await screen.findByText('user: ada@acme.test')).toBeInTheDocument()
  })

  it('is anonymous when the refresh cookie is missing', async () => {
    const server = fakeServer()
    signedOut(server)
    renderApp({ server, path: '/', routes })
    expect(await screen.findByText('status: anonymous')).toBeInTheDocument()
  })

  it('signs in and out', async () => {
    const server = fakeServer()
    signedOut(server)
    server
      .on('POST /auth/login', { body: { accessToken: 'tok', tokenType: 'Bearer', expiresIn: 900 } })
      .on('GET /me', { body: testProfile() })
      .on('POST /auth/logout', {})
    const { user } = renderApp({ server, path: '/', routes })
    await screen.findByText('status: anonymous')
    await user.click(screen.getByRole('button', { name: 'login' }))
    expect(await screen.findByText('user: ada@acme.test')).toBeInTheDocument()
    expect(server.callsTo('POST /auth/login')[0].body).toEqual({
      workspace: 'acme',
      email: 'ada@acme.test',
      password: 'pw',
    })
    await user.click(screen.getByRole('button', { name: 'logout' }))
    expect(await screen.findByText('status: anonymous')).toBeInTheDocument()
    expect(server.callsTo('POST /auth/logout')).toHaveLength(1)
  })

  it('signs out on an unrecoverable 401', async () => {
    const server = fakeServer()
    signedIn(server)
    server.on('GET /users', { status: 401, body: {} })
    const { user } = renderApp({ server, path: '/', routes })
    await screen.findByText('user: ada@acme.test')
    server.on('POST /auth/refresh', { status: 401, body: {} })
    await user.click(screen.getByRole('button', { name: 'call' }))
    await waitFor(() => expect(screen.getByText('status: anonymous')).toBeInTheDocument())
  })
})
```

`frontend/src/features/auth/guards.test.tsx`:

```tsx
import { screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { RouteObject } from 'react-router'
import { TenantRoot } from '@/app/TenantRoot'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, signedOut, testProfile } from '@/test/fixtures'
import { renderApp } from '@/test/renderApp'
import { RedirectIfTenantSignedIn, RequireTenantSession, safeNext } from './guards'
import { PERMISSIONS, RequirePermission } from './permissions'

const routes: RouteObject[] = [
  {
    element: <TenantRoot />,
    children: [
      {
        element: <RedirectIfTenantSignedIn />,
        children: [{ path: '/login', element: <h1>Sign in page</h1> }],
      },
      {
        element: <RequireTenantSession />,
        children: [
          { path: '/app', element: <h1>Overview page</h1> },
          {
            path: '/app/settings/users',
            element: (
              <RequirePermission anyOf={[PERMISSIONS.userRead]}>
                <h1>Users page</h1>
              </RequirePermission>
            ),
          },
        ],
      },
    ],
  },
]

describe('guards', () => {
  it('sends anonymous visitors to sign-in with a next parameter', async () => {
    const server = fakeServer()
    signedOut(server)
    const { router } = renderApp({ server, path: '/app/settings/users?status=ACTIVE', routes })
    expect(await screen.findByRole('heading', { name: 'Sign in page' })).toBeInTheDocument()
    expect(router.state.location.search).toBe(
      `?next=${encodeURIComponent('/app/settings/users?status=ACTIVE')}`,
    )
  })

  it('restores a deep link without showing the sign-in page', async () => {
    const server = fakeServer()
    signedIn(server)
    renderApp({ server, path: '/app/settings/users', routes })
    expect(await screen.findByRole('heading', { name: 'Users page' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Sign in page' })).not.toBeInTheDocument()
  })

  it('shows a no-access state for a missing permission', async () => {
    const server = fakeServer()
    signedIn(server, testProfile({ permissions: [] }))
    renderApp({ server, path: '/app/settings/users', routes })
    expect(
      await screen.findByRole('heading', { name: "You don't have access to this page" }),
    ).toBeInTheDocument()
  })

  it('sends signed-in users away from the sign-in page', async () => {
    const server = fakeServer()
    signedIn(server)
    renderApp({ server, path: '/login', routes })
    expect(await screen.findByRole('heading', { name: 'Overview page' })).toBeInTheDocument()
  })

  it('only honours local next targets under the given prefix', () => {
    expect(safeNext('/app/audit', '/app')).toBe('/app/audit')
    expect(safeNext('https://evil.example/app', '/app')).toBe('/app')
    expect(safeNext('//evil.example/app', '/app')).toBe('/app')
    expect(safeNext('/platform/tenants', '/app')).toBe('/app')
    expect(safeNext(null, '/platform/tenants')).toBe('/platform/tenants')
  })
})
```

Replace `frontend/src/app/App.test.tsx`. The old home-page test goes away, because `/` now redirects:

```tsx
import { screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { fakeServer } from '@/test/fakeServer'
import { signedOut } from '@/test/fixtures'
import { renderApp } from '@/test/renderApp'

describe('App routes', () => {
  it('renders a not-found page for unknown routes', async () => {
    const server = fakeServer()
    signedOut(server)
    renderApp({ server, path: '/nope' })
    expect(await screen.findByRole('heading', { name: /page not found/i })).toBeInTheDocument()
  })
})
```

- [ ] **Step 3: Run them to verify they fail**

Run: `cd frontend && npm test`
Expected: FAIL with import errors such as `Failed to resolve import "./problems"` and `"@/test/fakeServer"`.

- [ ] **Step 4: Write the API layer**

`frontend/src/lib/api/types.ts`:

```ts
/** Mirrors docs/api/openapi.json. Field names and value spellings are the server's. */
export interface Page<T> {
  items: T[]
  page: number
  size: number
  total: number
}

export type UserStatus = 'INVITED' | 'ACTIVE' | 'DISABLED'
export type TenantStatus = 'PENDING_VERIFICATION' | 'ACTIVE' | 'SUSPENDED'
export type InvitationStatus = 'PENDING' | 'ACCEPTED' | 'REVOKED' | 'EXPIRED'

export interface RoleRef {
  id: string
  name: string
}

export interface UserView {
  id: string
  email: string
  firstName: string
  lastName: string
  status: UserStatus
  emailVerified: boolean
  roles: RoleRef[]
  lastLoginAt: string | null
  createdAt: string
}

export interface TenantSummary {
  id: string
  slug: string
  name: string
  status: TenantStatus
  planCode: string
}

export interface Profile {
  user: UserView
  tenant: TenantSummary
  permissions: string[]
  modules: string[]
}

export interface TenantSettings extends TenantSummary {
  timezone: string
  locale: string
  currency: string
}

export interface ModuleState {
  code: string
  name: string
  enabled: boolean
}

export interface PermissionView {
  code: string
  module: string | null
  description: string
  moduleEnabled: boolean
}

export interface RoleView {
  id: string
  name: string
  description: string | null
  system: boolean
  permissions: string[]
}

export interface InvitationView {
  id: string
  email: string
  roleId: string
  roleName: string
  status: InvitationStatus
  invitedBy: string | null
  expiresAt: string
  createdAt: string
}

export interface InvitationPreview {
  workspace: string
  workspaceName: string
  email: string
  roleName: string
  expiresAt: string
}

export interface AcceptedInvitation {
  workspace: string
  email: string
}

export interface AuditEvent {
  id: string
  occurredAt: string
  actorType: string
  actorId: string | null
  action: string
  entityType: string | null
  entityId: string | null
  ip: string | null
  userAgent: string | null
  requestId: string | null
  correlationId: string | null
  before: unknown
  after: unknown
  metadata: unknown
}

export interface TokenResponse {
  accessToken: string
  tokenType: string
  expiresIn: number
}

export type PlatformRole = 'PLATFORM_ADMIN' | 'PLATFORM_SUPPORT'

export interface PlatformMe {
  id: string
  email: string
  role: PlatformRole
  permissions: string[]
}

export interface PlatformTenant {
  id: string
  slug: string
  name: string
  status: TenantStatus
  planCode: string
  createdAt: string
  activeUsers: number
  ownerEmails: string[]
}
```

`frontend/src/lib/api/problems.ts`:

```ts
import type { FieldValues, Path, UseFormSetError } from 'react-hook-form'
import { ApiError } from './client'

export const GENERIC_ERROR = 'Something went wrong. Please try again.'

/** The server's human-readable detail for a failed call, or a generic message (never a stack trace). */
export function problemMessage(error: unknown, fallback: string = GENERIC_ERROR): string {
  if (error instanceof ApiError && error.problem.detail) {
    return error.problem.detail
  }
  return fallback
}

/**
 * Puts server field errors (problem `errors[]`) on the matching form fields. Unknown fields are ignored.
 * Returns true when at least one message was applied, so callers can skip a duplicate form-level message.
 */
export function applyFieldErrors<T extends FieldValues>(
  error: unknown,
  setError: UseFormSetError<T>,
  fields: readonly Path<T>[],
): boolean {
  if (!(error instanceof ApiError) || !error.problem.errors?.length) return false
  let applied = false
  for (const { field, message } of error.problem.errors) {
    const match = fields.find((known) => known === field)
    if (match) {
      setError(match, { type: 'server', message })
      applied = true
    }
  }
  return applied
}
```

`frontend/src/lib/api/session.ts`:

```ts
import { createApiClient, type ApiClient } from './client'
import { createMemoryTokenStore, type TokenStore } from './tokenStore'
import type { TokenResponse } from './types'

export interface SessionApi {
  client: ApiClient
  tokens: TokenStore
  /**
   * One cookie-based refresh at boot. Memoized: React StrictMode mounts twice, and two concurrent
   * refreshes would race the server's single-use rotation (ADR-0003).
   */
  restore(): Promise<boolean>
  /** Called when a request's 401 could not be recovered by refreshing. Returns an unsubscribe. */
  onAuthFailure(listener: () => void): () => void
}

export interface SessionApiOptions {
  baseUrl?: string
  refreshPath: string
  fetchImpl?: typeof fetch
}

export function createSessionApi({
  baseUrl = '/api/v1',
  refreshPath,
  fetchImpl,
}: SessionApiOptions): SessionApi {
  const tokens = createMemoryTokenStore()
  const listeners = new Set<() => void>()

  async function refresh(): Promise<string | null> {
    const response = await client.post<TokenResponse>(refreshPath, undefined, {
      skipAuthRefresh: true,
    })
    return response.accessToken
  }

  const client: ApiClient = createApiClient({
    baseUrl,
    tokens,
    refresh,
    fetchImpl,
    onAuthFailure: () => listeners.forEach((listener) => listener()),
  })

  let restoring: Promise<boolean> | null = null

  return {
    client,
    tokens,
    restore() {
      restoring ??= refresh()
        .then((token) => {
          tokens.set(token)
          return token !== null
        })
        .catch(() => false)
      return restoring
    },
    onAuthFailure(listener) {
      listeners.add(listener)
      return () => {
        listeners.delete(listener)
      }
    },
  }
}
```

`frontend/src/lib/api/ApiContext.tsx`:

```tsx
import { createContext, useContext, type ReactNode } from 'react'
import type { ApiClient } from './client'

const ApiContext = createContext<ApiClient | null>(null)

/** The API client for this route subtree (tenant or platform). Pages call useApi(), never fetch(). */
export function ApiProvider({ client, children }: { client: ApiClient; children: ReactNode }) {
  return <ApiContext.Provider value={client}>{children}</ApiContext.Provider>
}

export function useApi(): ApiClient {
  const client = useContext(ApiContext)
  if (!client) throw new Error('useApi() used outside an ApiProvider')
  return client
}
```

`frontend/src/lib/format.ts`:

```ts
const dateTime = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' })

export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return '—'
  const date = new Date(iso)
  return Number.isNaN(date.getTime()) ? '—' : dateTime.format(date)
}

export function fullName(person: { firstName: string; lastName: string }): string {
  return `${person.firstName} ${person.lastName}`.trim()
}
```

- [ ] **Step 5: Write the session factory, tenant session, permissions and guards**

`frontend/src/lib/session/createSession.tsx`:

```tsx
import { useQueryClient } from '@tanstack/react-query'
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react'
import type { SessionApi } from '@/lib/api/session'
import type { TokenResponse } from '@/lib/api/types'

export type SessionState<P> =
  | { status: 'loading' }
  | { status: 'anonymous' }
  | { status: 'authenticated'; profile: P }

export interface Session<P, L> {
  state: SessionState<P>
  login(input: L): Promise<void>
  logout(): Promise<void>
  reloadProfile(): Promise<void>
  /** Forget the session locally, e.g. after a server-side "sign out everywhere". */
  clear(): void
}

export interface SessionPaths {
  login: string
  logout: string
  profile: string
}

/** One factory for both the tenant and the platform session (they differ only in paths and types). */
export function createSession<P, L>(paths: SessionPaths, name: string) {
  const Context = createContext<Session<P, L> | null>(null)

  function SessionProvider({ api, children }: { api: SessionApi; children: ReactNode }) {
    const queryClient = useQueryClient()
    const [state, setState] = useState<SessionState<P>>({ status: 'loading' })

    const clear = useCallback(() => {
      api.tokens.set(null)
      queryClient.clear()
      setState({ status: 'anonymous' })
    }, [api, queryClient])

    const reloadProfile = useCallback(async () => {
      const profile = await api.client.get<P>(paths.profile)
      setState({ status: 'authenticated', profile })
    }, [api])

    useEffect(() => api.onAuthFailure(clear), [api, clear])

    useEffect(() => {
      let cancelled = false
      void api.restore().then(async (restored) => {
        if (cancelled) return
        if (!restored) {
          setState({ status: 'anonymous' })
          return
        }
        try {
          const profile = await api.client.get<P>(paths.profile)
          if (!cancelled) setState({ status: 'authenticated', profile })
        } catch {
          if (!cancelled) setState({ status: 'anonymous' })
        }
      })
      return () => {
        cancelled = true
      }
    }, [api])

    const login = useCallback(
      async (input: L) => {
        const response = await api.client.post<TokenResponse>(paths.login, input, {
          skipAuthRefresh: true,
        })
        api.tokens.set(response.accessToken)
        queryClient.clear()
        await reloadProfile()
      },
      [api, queryClient, reloadProfile],
    )

    const logout = useCallback(async () => {
      try {
        await api.client.post(paths.logout, undefined, { skipAuthRefresh: true })
      } catch {
        // The cookie may already be gone; signing out locally is what matters.
      } finally {
        clear()
      }
    }, [api, clear])

    const value = useMemo<Session<P, L>>(
      () => ({ state, login, logout, reloadProfile, clear }),
      [state, login, logout, reloadProfile, clear],
    )
    return <Context.Provider value={value}>{children}</Context.Provider>
  }

  function useSession(): Session<P, L> {
    const session = useContext(Context)
    if (!session) throw new Error(`${name} session used outside its provider`)
    return session
  }

  return { SessionProvider, useSession }
}
```

`frontend/src/features/auth/tenantSession.ts`:

```ts
import type { Profile } from '@/lib/api/types'
import { createSession } from '@/lib/session/createSession'

export interface LoginInput {
  workspace: string
  email: string
  password: string
}

export const { SessionProvider: TenantSessionProvider, useSession: useTenantSession } =
  createSession<Profile, LoginInput>(
    { login: '/auth/login', logout: '/auth/logout', profile: '/me' },
    'Tenant',
  )
```

`frontend/src/features/auth/permissions.tsx`:

```tsx
import type { ReactNode } from 'react'
import { NoAccess } from '@/components/states'
import { useTenantSession } from './tenantSession'

/** Tenant permission codes (V3 catalog). The UI hides what these deny; the server enforces them. */
export const PERMISSIONS = {
  settingsRead: 'tenant.settings.read',
  settingsUpdate: 'tenant.settings.update',
  modulesManage: 'tenant.modules.manage',
  userRead: 'identity.user.read',
  userInvite: 'identity.user.invite',
  userUpdate: 'identity.user.update',
  userDisable: 'identity.user.disable',
  roleRead: 'authorization.role.read',
  roleManage: 'authorization.role.manage',
  roleAssign: 'authorization.role.assign',
  auditRead: 'audit.event.read',
} as const

export type PermissionCode = (typeof PERMISSIONS)[keyof typeof PERMISSIONS]

export const ALL_TENANT_PERMISSIONS: readonly PermissionCode[] = Object.values(PERMISSIONS)

/** Returns can(...codes): true when the signed-in user holds ANY of the codes. */
export function useCan(): (...codes: PermissionCode[]) => boolean {
  const { state } = useTenantSession()
  const held = state.status === 'authenticated' ? state.profile.permissions : []
  return (...codes) => codes.some((code) => held.includes(code))
}

export function RequirePermission({
  anyOf,
  children,
}: {
  anyOf: PermissionCode[]
  children: ReactNode
}) {
  const can = useCan()
  return can(...anyOf) ? <>{children}</> : <NoAccess />
}
```

`frontend/src/features/auth/guards.tsx`:

```tsx
import { Navigate, Outlet, useLocation, useSearchParams } from 'react-router'
import { FullPageLoading } from '@/components/states'
import { useTenantSession } from './tenantSession'

/** Only same-app paths under `prefix` are valid post-login targets (no open redirect). */
export function safeNext(next: string | null, prefix: string): string {
  if (next && next.startsWith(prefix) && !next.startsWith('//')) return next
  return prefix
}

export function RequireTenantSession() {
  const { state } = useTenantSession()
  const location = useLocation()
  if (state.status === 'loading') return <FullPageLoading label="Loading your workspace…" />
  if (state.status === 'anonymous') {
    const next = encodeURIComponent(location.pathname + location.search)
    return <Navigate to={`/login?next=${next}`} replace />
  }
  return <Outlet />
}

export function RedirectIfTenantSignedIn() {
  const { state } = useTenantSession()
  const [params] = useSearchParams()
  if (state.status === 'loading') return <FullPageLoading label="Loading…" />
  if (state.status === 'authenticated') return <Navigate to={safeNext(params.get('next'), '/app')} replace />
  return <Outlet />
}
```

- [ ] **Step 6: Write the handles, the tenant root, shared states and form components**

`frontend/src/app/handles.tsx`:

```tsx
import { createContext, useContext, type ReactNode } from 'react'
import { createSessionApi, type SessionApi } from '@/lib/api/session'

export interface ApiHandles {
  tenant: SessionApi
  platform: SessionApi
}

const HandlesContext = createContext<ApiHandles | null>(null)

export function createDefaultHandles(): ApiHandles {
  return {
    tenant: createSessionApi({ refreshPath: '/auth/refresh' }),
    platform: createSessionApi({ refreshPath: '/platform/auth/refresh' }),
  }
}

export function ApiHandlesProvider({ handles, children }: { handles: ApiHandles; children: ReactNode }) {
  return <HandlesContext.Provider value={handles}>{children}</HandlesContext.Provider>
}

export function useApiHandles(): ApiHandles {
  const handles = useContext(HandlesContext)
  if (!handles) throw new Error('useApiHandles() used outside ApiHandlesProvider')
  return handles
}
```

`frontend/src/app/TenantRoot.tsx`:

```tsx
import { Outlet } from 'react-router'
import { TenantSessionProvider } from '@/features/auth/tenantSession'
import { ApiProvider } from '@/lib/api/ApiContext'
import { useApiHandles } from './handles'

/** Everything outside /platform runs with the tenant API client and session. */
export function TenantRoot() {
  const { tenant } = useApiHandles()
  return (
    <ApiProvider client={tenant.client}>
      <TenantSessionProvider api={tenant}>
        <Outlet />
      </TenantSessionProvider>
    </ApiProvider>
  )
}
```

`frontend/src/components/states.tsx`:

```tsx
import type { ReactNode } from 'react'
import { Link } from 'react-router'
import { Button, buttonVariants } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { ApiError } from '@/lib/api/client'
import { problemMessage } from '@/lib/api/problems'

export function FullPageLoading({ label }: { label: string }) {
  return (
    <div role="status" aria-live="polite" className="flex min-h-screen items-center justify-center">
      <p className="text-sm text-muted-foreground">{label}</p>
    </div>
  )
}

export function ListSkeleton({ rows = 5 }: { rows?: number }) {
  return (
    <div role="status" aria-label="Loading" className="space-y-2">
      {Array.from({ length: rows }, (_, i) => (
        <Skeleton key={i} className="h-9 w-full" />
      ))}
    </div>
  )
}

export function ErrorState({ error, onRetry }: { error: unknown; onRetry?: () => void }) {
  const requestId = error instanceof ApiError ? error.problem.requestId : undefined
  return (
    <div role="alert" className="rounded-lg border border-destructive/30 p-4 text-sm">
      <p className="font-medium">{problemMessage(error)}</p>
      {requestId && <p className="mt-1 text-xs text-muted-foreground">Reference: {requestId}</p>}
      {onRetry && (
        <Button variant="outline" size="sm" className="mt-3" onClick={onRetry}>
          Retry
        </Button>
      )}
    </div>
  )
}

export function EmptyState({
  title,
  description,
  action,
}: {
  title: string
  description: string
  action?: ReactNode
}) {
  return (
    <div className="rounded-lg border border-dashed p-8 text-center">
      <p className="font-medium">{title}</p>
      <p className="mt-1 text-sm text-muted-foreground">{description}</p>
      {action && <div className="mt-4">{action}</div>}
    </div>
  )
}

export function NoAccess() {
  return (
    <section className="space-y-3">
      <h1 className="text-xl font-semibold">You don't have access to this page</h1>
      <p className="text-sm text-muted-foreground">
        Ask a workspace owner or admin to give your role the permission it needs.
      </p>
      <Link to="/app" className={buttonVariants({ variant: 'outline' })}>
        Back to overview
      </Link>
    </section>
  )
}

export function PageHeader({
  title,
  description,
  actions,
}: {
  title: string
  description?: string
  actions?: ReactNode
}) {
  return (
    <header className="mb-6 flex flex-wrap items-start justify-between gap-3">
      <div>
        <h1 className="text-xl font-semibold">{title}</h1>
        {description && <p className="mt-1 text-sm text-muted-foreground">{description}</p>}
      </div>
      {actions && <div className="flex gap-2">{actions}</div>}
    </header>
  )
}
```

`frontend/src/components/form/Field.tsx`:

```tsx
import type { ReactNode } from 'react'
import { Label } from '@/components/ui/label'

/**
 * Label + control + hint + error. The control must use id={id}, aria-invalid={!!error} and
 * aria-describedby={describedBy(id, error, hint)} so screen readers announce the message.
 */
export function Field({
  id,
  label,
  error,
  hint,
  children,
}: {
  id: string
  label: string
  error?: string
  hint?: string
  children: ReactNode
}) {
  return (
    <div className="space-y-1.5">
      <Label htmlFor={id}>{label}</Label>
      {children}
      {hint && !error && (
        <p id={`${id}-hint`} className="text-xs text-muted-foreground">
          {hint}
        </p>
      )}
      {error && (
        <p id={`${id}-error`} role="alert" className="text-xs text-destructive">
          {error}
        </p>
      )}
    </div>
  )
}

export function describedBy(id: string, error?: string, hint?: string): string | undefined {
  if (error) return `${id}-error`
  if (hint) return `${id}-hint`
  return undefined
}
```

`frontend/src/components/form/NativeSelect.tsx`:

```tsx
import { forwardRef, type SelectHTMLAttributes } from 'react'
import { cn } from '@/lib/utils'

/** A native <select> styled like Input: accessible, keyboard-friendly and testable in jsdom. */
export const NativeSelect = forwardRef<HTMLSelectElement, SelectHTMLAttributes<HTMLSelectElement>>(
  function NativeSelect({ className, ...props }, ref) {
    return (
      <select
        ref={ref}
        className={cn(
          'h-8 w-full rounded-lg border border-input bg-background px-2.5 text-sm outline-none',
          'focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50',
          'aria-invalid:border-destructive disabled:opacity-50',
          className,
        )}
        {...props}
      />
    )
  },
)
```

`frontend/src/components/form/Checkbox.tsx`:

```tsx
import { forwardRef, type InputHTMLAttributes } from 'react'
import { cn } from '@/lib/utils'

/** A native checkbox; pass role="switch" for on/off toggles. */
export const Checkbox = forwardRef<HTMLInputElement, Omit<InputHTMLAttributes<HTMLInputElement>, 'type'>>(
  function Checkbox({ className, ...props }, ref) {
    return (
      <input
        ref={ref}
        type="checkbox"
        className={cn('size-4 shrink-0 rounded border-input accent-primary disabled:opacity-50', className)}
        {...props}
      />
    )
  },
)
```

- [ ] **Step 7: Write the app wiring, router, page meta and test harness**

Replace `frontend/src/app/App.tsx`:

```tsx
import { QueryClientProvider } from '@tanstack/react-query'
import { createBrowserRouter, RouterProvider } from 'react-router'
import { Toaster } from '@/components/ui/sonner'
import { ApiHandlesProvider, createDefaultHandles } from './handles'
import { queryClient } from './queryClient'
import { routes } from './router'

const router = createBrowserRouter(routes)
const handles = createDefaultHandles()

export function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <ApiHandlesProvider handles={handles}>
        <RouterProvider router={router} />
        <Toaster richColors closeButton />
      </ApiHandlesProvider>
    </QueryClientProvider>
  )
}
```

Replace `frontend/src/app/router.tsx`. Later tasks add their routes inside the marked children arrays.

```tsx
import { Navigate, type RouteObject } from 'react-router'
import { RedirectIfTenantSignedIn, RequireTenantSession } from '@/features/auth/guards'
import { NotFoundPage } from '@/pages/NotFoundPage'
import { TenantRoot } from './TenantRoot'

export const routes: RouteObject[] = [
  {
    element: <TenantRoot />,
    children: [
      { path: '/', element: <Navigate to="/app" replace /> },
      {
        element: <RedirectIfTenantSignedIn />,
        children: [
          // public sign-in/sign-up routes (Task 2)
        ],
      },
      {
        element: <RequireTenantSession />,
        children: [
          // the workspace shell and its pages (Tasks 4–9)
        ],
      },
    ],
  },
  { path: '*', element: <NotFoundPage /> },
]
```

`/` redirects to `/app`. For anonymous visitors, `RequireTenantSession` then sends them on to `/login`. Delete `frontend/src/pages/HomePage.tsx`; it is no longer routed.

In `frontend/index.html` `<head>`, add `<meta name="referrer" content="no-referrer" />`. In `frontend/nginx.conf`, change `Referrer-Policy strict-origin-when-cross-origin` to `Referrer-Policy no-referrer`.

Replace `frontend/src/test/setup.ts`. jsdom lacks APIs that sonner and Base UI touch:

```ts
import '@testing-library/jest-dom/vitest'

// jsdom gaps used by sonner (prefers-color-scheme) and Base UI popups.
if (!window.matchMedia) {
  window.matchMedia = (query: string): MediaQueryList =>
    ({
      matches: false,
      media: query,
      onchange: null,
      addListener: () => undefined,
      removeListener: () => undefined,
      addEventListener: () => undefined,
      removeEventListener: () => undefined,
      dispatchEvent: () => false,
    }) as MediaQueryList
}
if (!('ResizeObserver' in window)) {
  class ResizeObserverStub {
    observe() {}
    unobserve() {}
    disconnect() {}
  }
  Object.assign(window, { ResizeObserver: ResizeObserverStub })
}
if (!Element.prototype.scrollIntoView) {
  Element.prototype.scrollIntoView = () => undefined
}
```

`frontend/src/test/fakeServer.ts`:

```ts
/** A route table behind fetchImpl: the real ApiClient runs against it. Routes: 'METHOD /path/:param'. */
export interface FakeRequest {
  route: string
  method: string
  path: string
  query: URLSearchParams
  params: Record<string, string>
  body: unknown
  headers: Headers
}

export interface FakeReply {
  status?: number
  body?: unknown
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

function toResponse({ status = 200, body }: FakeReply): Response {
  if (body === undefined) return new Response(null, { status: status === 200 ? 204 : status })
  const isProblem = status >= 400
  const payload = isProblem && typeof body === 'object' && body !== null ? { status, ...body } : body
  return new Response(JSON.stringify(payload), {
    status,
    headers: { 'Content-Type': isProblem ? 'application/problem+json' : 'application/json' },
  })
}
```

`frontend/src/test/fixtures.ts`:

```ts
import { ALL_TENANT_PERMISSIONS } from '@/features/auth/permissions'
import type { Profile, UserView } from '@/lib/api/types'
import type { FakeServer } from './fakeServer'

export function user(overrides: Partial<UserView> = {}): UserView {
  return {
    id: 'u-ada',
    email: 'ada@acme.test',
    firstName: 'Ada',
    lastName: 'Lovelace',
    status: 'ACTIVE',
    emailVerified: true,
    roles: [{ id: 'r-owner', name: 'TENANT_OWNER' }],
    lastLoginAt: '2026-10-06T09:00:00Z',
    createdAt: '2026-10-01T09:00:00Z',
    ...overrides,
  }
}

export function testProfile(overrides: Partial<Profile> = {}): Profile {
  return {
    user: user(),
    tenant: { id: 't-acme', slug: 'acme', name: 'Acme Inc', status: 'ACTIVE', planCode: 'FREE' },
    permissions: [...ALL_TENANT_PERMISSIONS],
    modules: [],
    ...overrides,
  }
}

export function signedIn(server: FakeServer, profile: Profile = testProfile()): FakeServer {
  return server
    .on('POST /auth/refresh', { body: { accessToken: 'test-token', tokenType: 'Bearer', expiresIn: 900 } })
    .on('GET /me', { body: profile })
}

export function signedOut(server: FakeServer): FakeServer {
  return server.on('POST /auth/refresh', {
    status: 401,
    body: { title: 'Unauthorized', detail: 'Your session has expired. Please sign in again.' },
  })
}
```

`frontend/src/test/renderApp.tsx`:

```tsx
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createMemoryRouter, RouterProvider, type RouteObject } from 'react-router'
import { Toaster } from '@/components/ui/sonner'
import { ApiHandlesProvider, type ApiHandles } from '@/app/handles'
import { routes as appRoutes } from '@/app/router'
import { createSessionApi } from '@/lib/api/session'
import type { FakeServer } from './fakeServer'

/** Renders routes exactly as App does, with both sessions talking to the fake server. */
export function renderApp({
  server,
  path,
  routes = appRoutes,
}: {
  server: FakeServer
  path: string
  routes?: RouteObject[]
}) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  const handles: ApiHandles = {
    tenant: createSessionApi({ refreshPath: '/auth/refresh', fetchImpl: server.fetchImpl }),
    platform: createSessionApi({ refreshPath: '/platform/auth/refresh', fetchImpl: server.fetchImpl }),
  }
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  const user = userEvent.setup()
  const utils = render(
    <QueryClientProvider client={queryClient}>
      <ApiHandlesProvider handles={handles}>
        <RouterProvider router={router} />
        <Toaster />
      </ApiHandlesProvider>
    </QueryClientProvider>,
  )
  return { ...utils, user, router, server, handles, queryClient }
}
```

- [ ] **Step 8: Run the tests to verify they pass, then the full gate**

Run: `cd frontend && npm test`
Expected: PASS for all new tests, `client.test.ts` (unchanged) and `App.test.tsx`.

Run: `make test-frontend`
Expected: PASS for format:check, lint, typecheck, test and build. If oxlint warns `only-export-components` about `createSession.tsx` or `tenantSession.ts`, that is acceptable (warning level). Errors are not.

- [ ] **Step 9: Commit**

```bash
git add frontend
git commit -m "feat(frontend): tenant session plumbing, API helpers, shared states and test harness"
```

---
### Task 2: Public pages — sign up, verify email, sign in

**Files:**
- Create: `frontend/src/features/auth/schemas.ts`
- Create: `frontend/src/features/auth/AuthCard.tsx`
- Create: `frontend/src/features/auth/ResendVerification.tsx`
- Create: `frontend/src/features/auth/SignupPage.tsx`
- Create: `frontend/src/features/auth/VerifyEmailPage.tsx`
- Create: `frontend/src/features/auth/LoginPage.tsx`
- Create: `frontend/src/features/auth/routes.tsx`
- Create: `frontend/src/components/form/TextField.tsx`
- Create: `frontend/src/components/form/FormError.tsx`
- Modify: `frontend/src/app/router.tsx`
- Test: `frontend/src/features/auth/SignupPage.test.tsx`, `VerifyEmailPage.test.tsx`, `LoginPage.test.tsx`, plus `frontend/src/test/authRoutes.tsx` (a test helper)

**Interfaces:**
- Consumes (Task 1):
  - `useApi`, `useTenantSession`, `applyFieldErrors`, `problemMessage`;
  - `Field`, `describedBy`;
  - `fakeServer`, `renderApp`, `signedIn`, `signedOut`, `testProfile`;
  - `RedirectIfTenantSignedIn`, `RequireTenantSession`, `TenantRoot`.
- Produces:
  - `MESSAGES`, `emailField`, `requiredText(max)`, `newPasswordField`, `slugField` (from `schemas.ts`);
  - `AuthCard`, `ResendVerification`;
  - `TextField` (generic RHF text input) and `FormError`;
  - route arrays `signInRoutes` (sign-up, sign-in: redirect when signed in) and `linkRoutes` (verify email, accept invitation: always reachable).

- [ ] **Step 1: Write the failing tests**

`frontend/src/test/authRoutes.tsx` (test helper: the real public routes plus a stub app home):

```tsx
import type { RouteObject } from 'react-router'
import { TenantRoot } from '@/app/TenantRoot'
import { RedirectIfTenantSignedIn, RequireTenantSession } from '@/features/auth/guards'
import { linkRoutes, signInRoutes } from '@/features/auth/routes'

export const authTestRoutes: RouteObject[] = [
  {
    element: <TenantRoot />,
    children: [
      { element: <RedirectIfTenantSignedIn />, children: signInRoutes },
      ...linkRoutes,
      {
        element: <RequireTenantSession />,
        children: [{ path: '/app/*', element: <h1>App home</h1> }],
      },
    ],
  },
]
```

`frontend/src/features/auth/SignupPage.test.tsx`:

```tsx
import { screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { authTestRoutes } from '@/test/authRoutes'
import { fakeServer } from '@/test/fakeServer'
import { signedOut } from '@/test/fixtures'
import { renderApp } from '@/test/renderApp'

function setup() {
  const server = fakeServer()
  signedOut(server)
  return renderApp({ server, path: '/signup', routes: authTestRoutes })
}

describe('SignupPage', () => {
  it('validates before calling the server', async () => {
    const { user, server } = setup()
    await user.click(await screen.findByRole('button', { name: 'Create workspace' }))
    expect(await screen.findAllByText('Required.')).not.toHaveLength(0)
    await user.type(screen.getByLabelText('Work email'), 'not-an-email')
    await user.type(screen.getByLabelText('Password'), 'short')
    await user.click(screen.getByRole('button', { name: 'Create workspace' }))
    expect(await screen.findByText('Enter a valid email address.')).toBeInTheDocument()
    expect(screen.getByText('Use at least 12 characters.')).toBeInTheDocument()
    expect(server.callsTo('POST /auth/signup')).toHaveLength(0)
  })

  it('suggests a workspace URL from the name until the user edits it', async () => {
    const { user } = setup()
    await user.type(await screen.findByLabelText('Workspace name'), 'Acme Trading Co.')
    expect(screen.getByLabelText('Workspace URL')).toHaveValue('acme-trading-co')
  })

  it('shows a server field error under the field and keeps the other values', async () => {
    const { user, server } = setup()
    server.on('POST /auth/signup', {
      status: 409,
      body: { detail: 'Conflict', errors: [{ field: 'slug', message: 'This workspace URL is already taken.' }] },
    })
    await fill(user)
    await user.click(screen.getByRole('button', { name: 'Create workspace' }))
    expect(await screen.findByText('This workspace URL is already taken.')).toBeInTheDocument()
    expect(screen.getByLabelText('Workspace URL')).toHaveAttribute('aria-invalid', 'true')
    expect(screen.getByLabelText('Work email')).toHaveValue('ada@acme.test')
  })

  it('asks the user to check their email, and can resend', async () => {
    const { user, server } = setup()
    server
      .on('POST /auth/signup', { status: 201, body: { slug: 'acme', status: 'PENDING_VERIFICATION' } })
      .on('POST /auth/resend-verification', { status: 202 })
    await fill(user)
    await user.click(screen.getByRole('button', { name: 'Create workspace' }))
    expect(await screen.findByRole('heading', { name: 'Check your email' })).toBeInTheDocument()
    expect(server.callsTo('POST /auth/signup')[0].body).toEqual({
      workspaceName: 'Acme Inc',
      slug: 'acme',
      firstName: 'Ada',
      lastName: 'Lovelace',
      email: 'ada@acme.test',
      password: 'correct horse battery',
    })
    await user.click(screen.getByRole('button', { name: 'Resend the email' }))
    expect(await screen.findByText(/we've sent a new link/i)).toBeInTheDocument()
    expect(server.callsTo('POST /auth/resend-verification')[0].body).toEqual({
      workspace: 'acme',
      email: 'ada@acme.test',
    })
  })
})

async function fill(user: ReturnType<typeof setup>['user']) {
  await user.type(await screen.findByLabelText('Workspace name'), 'Acme Inc')
  await user.clear(screen.getByLabelText('Workspace URL'))
  await user.type(screen.getByLabelText('Workspace URL'), 'acme')
  await user.type(screen.getByLabelText('First name'), 'Ada')
  await user.type(screen.getByLabelText('Last name'), 'Lovelace')
  await user.type(screen.getByLabelText('Work email'), 'ada@acme.test')
  await user.type(screen.getByLabelText('Password'), 'correct horse battery')
}
```

`frontend/src/features/auth/VerifyEmailPage.test.tsx`:

```tsx
import { screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { authTestRoutes } from '@/test/authRoutes'
import { fakeServer } from '@/test/fakeServer'
import { signedOut } from '@/test/fixtures'
import { renderApp } from '@/test/renderApp'

describe('VerifyEmailPage', () => {
  it('verifies the token once and removes it from the address bar', async () => {
    const server = fakeServer()
    signedOut(server).on('POST /auth/verify-email', { status: 204 })
    const { router } = renderApp({ server, path: '/verify-email?token=abc.def', routes: authTestRoutes })
    expect(await screen.findByRole('heading', { name: 'Email verified' })).toBeInTheDocument()
    expect(server.callsTo('POST /auth/verify-email')).toHaveLength(1)
    expect(server.callsTo('POST /auth/verify-email')[0].body).toEqual({ token: 'abc.def' })
    expect(router.state.location.search).toBe('')
    expect(screen.getByRole('link', { name: 'Sign in' })).toHaveAttribute('href', '/login')
  })

  it('explains a bad link and offers to resend', async () => {
    const server = fakeServer()
    signedOut(server)
      .on('POST /auth/verify-email', {
        status: 400,
        body: { detail: 'This verification link is invalid or has expired.' },
      })
      .on('POST /auth/resend-verification', { status: 202 })
    const { user } = renderApp({ server, path: '/verify-email?token=bad', routes: authTestRoutes })
    expect(await screen.findByText('This verification link is invalid or has expired.')).toBeInTheDocument()
    await user.type(screen.getByLabelText('Workspace URL'), 'acme')
    await user.type(screen.getByLabelText('Work email'), 'ada@acme.test')
    await user.click(screen.getByRole('button', { name: 'Resend the email' }))
    expect(await screen.findByText(/we've sent a new link/i)).toBeInTheDocument()
  })

  it('handles a link without a token', async () => {
    const server = fakeServer()
    signedOut(server)
    renderApp({ server, path: '/verify-email', routes: authTestRoutes })
    expect(await screen.findByText('This verification link is incomplete.')).toBeInTheDocument()
  })
})
```

`frontend/src/features/auth/LoginPage.test.tsx`:

```tsx
import { screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { authTestRoutes } from '@/test/authRoutes'
import { fakeServer } from '@/test/fakeServer'
import { signedOut, testProfile } from '@/test/fixtures'
import { renderApp } from '@/test/renderApp'

function setup(path = '/login') {
  const server = fakeServer()
  signedOut(server)
  return renderApp({ server, path, routes: authTestRoutes })
}

async function signIn(user: ReturnType<typeof setup>['user']) {
  await user.type(await screen.findByLabelText('Workspace URL'), 'acme')
  await user.type(screen.getByLabelText('Email'), 'ada@acme.test')
  await user.type(screen.getByLabelText('Password'), 'correct horse battery')
  await user.click(screen.getByRole('button', { name: 'Sign in' }))
}

describe('LoginPage', () => {
  it('signs in and returns to next', async () => {
    const { user, server, router } = setup(`/login?next=${encodeURIComponent('/app/audit')}`)
    server
      .on('POST /auth/login', { body: { accessToken: 'tok', tokenType: 'Bearer', expiresIn: 900 } })
      .on('GET /me', { body: testProfile() })
    await signIn(user)
    expect(await screen.findByRole('heading', { name: 'App home' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/app/audit')
    expect(server.callsTo('POST /auth/login')[0].body).toEqual({
      workspace: 'acme',
      email: 'ada@acme.test',
      password: 'correct horse battery',
    })
  })

  it('shows the uniform failure message', async () => {
    const { user, server } = setup()
    server.on('POST /auth/login', { status: 401, body: { detail: 'Invalid workspace, email or password.' } })
    await signIn(user)
    expect(await screen.findByText('Invalid workspace, email or password.')).toBeInTheDocument()
    expect(screen.getByLabelText('Email')).toHaveValue('ada@acme.test')
  })

  it('shows a suspended workspace', async () => {
    const { user, server } = setup()
    server.on('POST /auth/login', { status: 403, body: { detail: 'Workspace suspended.' } })
    await signIn(user)
    expect(await screen.findByText('Workspace suspended.')).toBeInTheDocument()
  })

  it('offers to resend verification for an unverified address', async () => {
    const { user, server } = setup()
    server
      .on('POST /auth/login', { status: 403, body: { detail: 'Email address not verified.' } })
      .on('POST /auth/resend-verification', { status: 202 })
    await signIn(user)
    await user.click(await screen.findByRole('button', { name: 'Resend the email' }))
    expect(await screen.findByText(/we've sent a new link/i)).toBeInTheDocument()
  })

  it('pre-fills the workspace and email from the link', async () => {
    setup('/login?workspace=acme&email=ada%40acme.test')
    expect(await screen.findByLabelText('Workspace URL')).toHaveValue('acme')
    expect(screen.getByLabelText('Email')).toHaveValue('ada@acme.test')
  })
})
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd frontend && npm test -- src/features/auth`
Expected: FAIL with `Failed to resolve import "@/features/auth/routes"`.

- [ ] **Step 3: Write schemas and form components**

`frontend/src/features/auth/schemas.ts`:

```ts
import { z } from 'zod'

export const MESSAGES = {
  required: 'Required.',
  email: 'Enter a valid email address.',
  password: 'Use at least 12 characters.',
  passwordsDiffer: "The passwords don't match.",
} as const

/** Same shape the server checks (Emails.SHAPE); the server stays the authority. */
const EMAIL = /^[^@\s]+@[^@\s]+\.[^@\s]+$/

export const requiredText = (max: number) =>
  z.string().trim().min(1, MESSAGES.required).max(max, `Use at most ${max} characters.`)

export const emailField = z
  .string()
  .trim()
  .min(1, MESSAGES.required)
  .max(254, 'Use at most 254 characters.')
  .regex(EMAIL, MESSAGES.email)

export const newPasswordField = z
  .string()
  .min(12, MESSAGES.password)
  .max(128, 'Use at most 128 characters.')

export const slugField = z
  .string()
  .trim()
  .min(3, 'Use 3 to 40 characters.')
  .max(40, 'Use 3 to 40 characters.')
  .regex(/^[a-z0-9]+(-[a-z0-9]+)*$/, 'Use lowercase letters, numbers and single hyphens.')

/** "Acme Trading Co." → "acme-trading-co" (a suggestion; the user can edit it). */
export function slugify(name: string): string {
  return name
    .toLowerCase()
    .normalize('NFKD')
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 40)
    .replace(/-+$/g, '')
}
```

`frontend/src/components/form/TextField.tsx`:

```tsx
import type { FieldValues, Path, UseFormReturn } from 'react-hook-form'
import { Input } from '@/components/ui/input'
import { describedBy, Field } from './Field'

/** A labelled react-hook-form text input with accessible error wiring. */
export function TextField<T extends FieldValues>({
  form,
  name,
  label,
  type = 'text',
  autoComplete,
  hint,
  inputMode,
  maxLength,
  onValueChange,
}: {
  form: UseFormReturn<T>
  name: Path<T>
  label: string
  type?: 'text' | 'email' | 'password'
  autoComplete?: string
  hint?: string
  inputMode?: 'text' | 'numeric' | 'email'
  maxLength?: number
  onValueChange?: (value: string) => void
}) {
  const id = `field-${name}`
  const error = form.getFieldState(name, form.formState).error?.message
  return (
    <Field id={id} label={label} error={error} hint={hint}>
      <Input
        id={id}
        type={type}
        autoComplete={autoComplete}
        inputMode={inputMode}
        maxLength={maxLength}
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy(id, error, hint)}
        {...form.register(name, {
          onChange: onValueChange ? (event) => onValueChange(String(event.target.value)) : undefined,
        })}
      />
    </Field>
  )
}
```

`frontend/src/components/form/FormError.tsx`:

```tsx
export function FormError({ message }: { message: string | null }) {
  if (!message) return null
  return (
    <div
      role="alert"
      className="rounded-md border border-destructive/30 bg-destructive/5 px-3 py-2 text-sm text-destructive"
    >
      {message}
    </div>
  )
}
```

- [ ] **Step 4: Write the auth card, resend control and the three pages**

`frontend/src/features/auth/AuthCard.tsx`:

```tsx
import type { ReactNode } from 'react'
import { Card, CardContent, CardDescription, CardHeader } from '@/components/ui/card'

export function AuthCard({
  title,
  description,
  footer,
  children,
}: {
  title: string
  description?: ReactNode
  footer?: ReactNode
  children: ReactNode
}) {
  return (
    <main className="flex min-h-screen items-center justify-center bg-muted/30 px-4 py-10">
      <Card className="w-full max-w-md">
        <CardHeader>
          <p className="text-sm font-medium text-muted-foreground">NexusOps</p>
          <h1 className="text-2xl font-semibold">{title}</h1>
          {description && <CardDescription>{description}</CardDescription>}
        </CardHeader>
        <CardContent className="space-y-4">
          {children}
          {footer && <div className="text-sm text-muted-foreground">{footer}</div>}
        </CardContent>
      </Card>
    </main>
  )
}
```

`frontend/src/features/auth/ResendVerification.tsx`:

```tsx
import { zodResolver } from '@hookform/resolvers/zod'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { z } from 'zod'
import { Button } from '@/components/ui/button'
import { FormError } from '@/components/form/FormError'
import { TextField } from '@/components/form/TextField'
import { useApi } from '@/lib/api/ApiContext'
import { problemMessage } from '@/lib/api/problems'
import { emailField, requiredText } from './schemas'

const schema = z.object({ workspace: requiredText(64), email: emailField })
type Values = z.infer<typeof schema>

/**
 * POST /auth/resend-verification always answers 202 (no account discovery), so the confirmation text
 * is deliberately conditional. With `known` values it is a single button; otherwise it asks for them.
 */
export function ResendVerification({ known }: { known?: Values }) {
  const api = useApi()
  const [sent, setSent] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const form = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: known ?? { workspace: '', email: '' },
  })

  const send = form.handleSubmit(async (values) => {
    setError(null)
    try {
      await api.post('/auth/resend-verification', values, { skipAuthRefresh: true })
      setSent(true)
    } catch (e) {
      setError(problemMessage(e))
    }
  })

  if (sent) {
    return (
      <p role="status" className="text-sm">
        If that account is waiting for verification, we've sent a new link. Check your inbox.
      </p>
    )
  }
  return (
    <form noValidate onSubmit={send} className="space-y-3">
      {!known && (
        <>
          <TextField form={form} name="workspace" label="Workspace URL" autoComplete="organization" />
          <TextField form={form} name="email" label="Work email" type="email" autoComplete="email" />
        </>
      )}
      <FormError message={error} />
      <Button type="submit" variant="outline" disabled={form.formState.isSubmitting}>
        Resend the email
      </Button>
    </form>
  )
}
```

`frontend/src/features/auth/SignupPage.tsx`:

```tsx
import { zodResolver } from '@hookform/resolvers/zod'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { Link } from 'react-router'
import { z } from 'zod'
import { Button } from '@/components/ui/button'
import { FormError } from '@/components/form/FormError'
import { TextField } from '@/components/form/TextField'
import { useApi } from '@/lib/api/ApiContext'
import { applyFieldErrors, problemMessage } from '@/lib/api/problems'
import { AuthCard } from './AuthCard'
import { ResendVerification } from './ResendVerification'
import { emailField, newPasswordField, requiredText, slugField, slugify } from './schemas'

const schema = z.object({
  workspaceName: requiredText(120),
  slug: slugField,
  firstName: requiredText(80),
  lastName: requiredText(80),
  email: emailField,
  password: newPasswordField,
})
type Values = z.infer<typeof schema>
const FIELDS = ['workspaceName', 'slug', 'firstName', 'lastName', 'email', 'password'] as const

export function SignupPage() {
  const api = useApi()
  const [created, setCreated] = useState<{ workspace: string; email: string } | null>(null)
  const [formError, setFormError] = useState<string | null>(null)
  const form = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: { workspaceName: '', slug: '', firstName: '', lastName: '', email: '', password: '' },
  })

  const submit = form.handleSubmit(async (values) => {
    setFormError(null)
    try {
      const result = await api.post<{ slug: string }>('/auth/signup', values, { skipAuthRefresh: true })
      setCreated({ workspace: result.slug, email: values.email })
    } catch (error) {
      if (!applyFieldErrors(error, form.setError, FIELDS)) setFormError(problemMessage(error))
    }
  })

  if (created) {
    return (
      <AuthCard
        title="Check your email"
        description={`We sent a verification link to ${created.email}. Open it to activate your workspace.`}
        footer={<Link to="/login" className="underline">Back to sign in</Link>}
      >
        <ResendVerification known={created} />
      </AuthCard>
    )
  }

  return (
    <AuthCard
      title="Create your workspace"
      description="Start on the Free plan. You can invite your team once you're in."
      footer={
        <>
          Already have a workspace?{' '}
          <Link to="/login" className="underline">
            Sign in
          </Link>
        </>
      }
    >
      <form noValidate onSubmit={submit} className="space-y-4">
        <TextField
          form={form}
          name="workspaceName"
          label="Workspace name"
          autoComplete="organization"
          onValueChange={(name) => {
            if (!form.getFieldState('slug').isDirty) form.setValue('slug', slugify(name))
          }}
        />
        <TextField
          form={form}
          name="slug"
          label="Workspace URL"
          hint="Lowercase letters, numbers and hyphens. You'll use it to sign in."
        />
        <div className="grid gap-4 sm:grid-cols-2">
          <TextField form={form} name="firstName" label="First name" autoComplete="given-name" />
          <TextField form={form} name="lastName" label="Last name" autoComplete="family-name" />
        </div>
        <TextField form={form} name="email" label="Work email" type="email" autoComplete="email" />
        <TextField
          form={form}
          name="password"
          label="Password"
          type="password"
          autoComplete="new-password"
          hint="At least 12 characters."
        />
        <FormError message={formError} />
        <Button type="submit" className="w-full" disabled={form.formState.isSubmitting}>
          Create workspace
        </Button>
      </form>
    </AuthCard>
  )
}
```

`frontend/src/features/auth/VerifyEmailPage.tsx`:

```tsx
import { useEffect, useRef, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router'
import { buttonVariants } from '@/components/ui/button'
import { useApi } from '@/lib/api/ApiContext'
import { problemMessage } from '@/lib/api/problems'
import { AuthCard } from './AuthCard'
import { ResendVerification } from './ResendVerification'

type Outcome = { kind: 'verifying' } | { kind: 'verified' } | { kind: 'failed'; message: string }

export function VerifyEmailPage() {
  const api = useApi()
  const navigate = useNavigate()
  const [params] = useSearchParams()
  const [token] = useState(() => params.get('token'))
  const [outcome, setOutcome] = useState<Outcome>(() =>
    token ? { kind: 'verifying' } : { kind: 'failed', message: 'This verification link is incomplete.' },
  )
  const started = useRef(false)

  useEffect(() => {
    if (!token || started.current) return // single-use token: StrictMode re-runs effects, refs persist
    started.current = true
    navigate('/verify-email', { replace: true }) // drop the token from the address bar and history
    api
      .post('/auth/verify-email', { token }, { skipAuthRefresh: true })
      .then(() => setOutcome({ kind: 'verified' }))
      .catch((error: unknown) => setOutcome({ kind: 'failed', message: problemMessage(error) }))
  }, [api, navigate, token])

  if (outcome.kind === 'verifying') {
    return (
      <AuthCard title="Verifying your email">
        <p role="status" className="text-sm text-muted-foreground">
          One moment…
        </p>
      </AuthCard>
    )
  }
  if (outcome.kind === 'verified') {
    return (
      <AuthCard title="Email verified" description="Your workspace is ready.">
        <Link to="/login" className={buttonVariants({ className: 'w-full' })}>
          Sign in
        </Link>
      </AuthCard>
    )
  }
  return (
    <AuthCard
      title="We couldn't verify your email"
      footer={<Link to="/login" className="underline">Back to sign in</Link>}
    >
      <p role="alert" className="text-sm">
        {outcome.message}
      </p>
      <p className="text-sm text-muted-foreground">Request a fresh link:</p>
      <ResendVerification />
    </AuthCard>
  )
}
```

`frontend/src/features/auth/LoginPage.tsx`:

```tsx
import { zodResolver } from '@hookform/resolvers/zod'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { Link, useSearchParams } from 'react-router'
import { z } from 'zod'
import { Button } from '@/components/ui/button'
import { FormError } from '@/components/form/FormError'
import { TextField } from '@/components/form/TextField'
import { ApiError } from '@/lib/api/client'
import { problemMessage } from '@/lib/api/problems'
import { AuthCard } from './AuthCard'
import { ResendVerification } from './ResendVerification'
import { emailField, MESSAGES, requiredText } from './schemas'
import { useTenantSession } from './tenantSession'

const schema = z.object({
  workspace: requiredText(64),
  email: emailField,
  password: z.string().min(1, MESSAGES.required),
})
type Values = z.infer<typeof schema>

/** After a successful sign-in, RedirectIfTenantSignedIn sends the user to ?next (or /app). */
export function LoginPage() {
  const session = useTenantSession()
  const [params] = useSearchParams()
  const [error, setError] = useState<string | null>(null)
  const [unverified, setUnverified] = useState<{ workspace: string; email: string } | null>(null)
  const form = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: {
      workspace: params.get('workspace') ?? '',
      email: params.get('email') ?? '',
      password: '',
    },
  })

  const submit = form.handleSubmit(async (values) => {
    setError(null)
    setUnverified(null)
    try {
      await session.login(values)
    } catch (e) {
      setError(problemMessage(e))
      if (e instanceof ApiError && e.problem.detail === 'Email address not verified.') {
        setUnverified({ workspace: values.workspace, email: values.email })
      }
    }
  })

  return (
    <AuthCard
      title="Sign in"
      description={params.get('next') ? 'Please sign in to continue.' : 'Welcome back.'}
      footer={
        <>
          New to NexusOps?{' '}
          <Link to="/signup" className="underline">
            Create a workspace
          </Link>
        </>
      }
    >
      <form noValidate onSubmit={submit} className="space-y-4">
        <TextField form={form} name="workspace" label="Workspace URL" autoComplete="organization" />
        <TextField form={form} name="email" label="Email" type="email" autoComplete="username" />
        <TextField
          form={form}
          name="password"
          label="Password"
          type="password"
          autoComplete="current-password"
        />
        <FormError message={error} />
        <Button type="submit" className="w-full" disabled={form.formState.isSubmitting}>
          Sign in
        </Button>
      </form>
      {unverified && <ResendVerification known={unverified} />}
    </AuthCard>
  )
}
```

`frontend/src/features/auth/routes.tsx`. Task 3 adds the accept-invitation route to `linkRoutes`.

```tsx
import type { RouteObject } from 'react-router'
import { LoginPage } from './LoginPage'
import { SignupPage } from './SignupPage'
import { VerifyEmailPage } from './VerifyEmailPage'

/** Sign-in/sign-up: wrapped in RedirectIfTenantSignedIn by the router. */
export const signInRoutes: RouteObject[] = [
  { path: '/login', element: <LoginPage /> },
  { path: '/signup', element: <SignupPage /> },
]

/** Pages opened from emailed links: always reachable, signed in or not. */
export const linkRoutes: RouteObject[] = [{ path: '/verify-email', element: <VerifyEmailPage /> }]
```

In `frontend/src/app/router.tsx`:
- import `linkRoutes` and `signInRoutes`;
- set the `RedirectIfTenantSignedIn` children to `signInRoutes` (replacing the Task 1 comment);
- add `...linkRoutes` as siblings right after that object, inside `TenantRoot`'s children.

- [ ] **Step 5: Run the tests to verify they pass, then the full gate**

Run: `cd frontend && npm test -- src/features/auth`
Expected: PASS (13 tests).

Run: `make test-frontend`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add frontend
git commit -m "feat(frontend): sign up, email verification and sign in"
```

---

### Task 3: Accept an invitation

**Files:**
- Create: `frontend/src/features/auth/AcceptInvitationPage.tsx`
- Modify: `frontend/src/features/auth/routes.tsx`
- Test: `frontend/src/features/auth/AcceptInvitationPage.test.tsx`

**Interfaces:**
- Consumes:
  - Task 1: `useApi`, `InvitationPreview`, `AcceptedInvitation`, `applyFieldErrors`, `problemMessage`, `ListSkeleton`.
  - Task 2: `AuthCard`, `TextField`, `FormError`, `requiredText`, `newPasswordField`, `MESSAGES`, `authTestRoutes`.
- Produces: the route `/invite/accept` in `linkRoutes`.

- [ ] **Step 1: Write the failing test**

`frontend/src/features/auth/AcceptInvitationPage.test.tsx`:

```tsx
import { screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { authTestRoutes } from '@/test/authRoutes'
import { fakeServer } from '@/test/fakeServer'
import { signedOut } from '@/test/fixtures'
import { renderApp } from '@/test/renderApp'

const PREVIEW = {
  workspace: 'acme',
  workspaceName: 'Acme Inc',
  email: 'grace@acme.test',
  roleName: 'Support',
  expiresAt: '2026-10-13T09:00:00Z',
}

function setup(path = '/invite/accept?token=t0k.en') {
  const server = fakeServer()
  signedOut(server).on('GET /invitations/preview', { body: PREVIEW })
  return renderApp({ server, path, routes: authTestRoutes })
}

async function fillAndSubmit(user: ReturnType<typeof setup>['user'], repeat = 'a long enough passphrase') {
  await user.type(await screen.findByLabelText('First name'), 'Grace')
  await user.type(screen.getByLabelText('Last name'), 'Hopper')
  await user.type(screen.getByLabelText('Password'), 'a long enough passphrase')
  await user.type(screen.getByLabelText('Repeat password'), repeat)
  await user.click(screen.getByRole('button', { name: 'Join Acme Inc' }))
}

describe('AcceptInvitationPage', () => {
  it('previews the invitation, accepts it and links to sign-in', async () => {
    const { user, server, router } = setup()
    server.on('POST /invitations/accept', {
      status: 201,
      body: { workspace: 'acme', email: 'grace@acme.test' },
    })
    expect(await screen.findByRole('heading', { name: 'Join Acme Inc' })).toBeInTheDocument()
    expect(screen.getByText(/grace@acme.test/)).toBeInTheDocument()
    expect(screen.getByText(/Support/)).toBeInTheDocument()
    expect(server.callsTo('GET /invitations/preview')[0].query.get('token')).toBe('t0k.en')
    expect(router.state.location.search).toBe('')
    await fillAndSubmit(user)
    expect(await screen.findByRole('heading', { name: "You're in" })).toBeInTheDocument()
    expect(server.callsTo('POST /invitations/accept')[0].body).toEqual({
      token: 't0k.en',
      firstName: 'Grace',
      lastName: 'Hopper',
      password: 'a long enough passphrase',
    })
    expect(screen.getByRole('link', { name: 'Sign in to Acme Inc' })).toHaveAttribute(
      'href',
      '/login?workspace=acme&email=grace%40acme.test',
    )
  })

  it('explains an invalid or expired invitation', async () => {
    const server = fakeServer()
    signedOut(server).on('GET /invitations/preview', {
      status: 400,
      body: { detail: 'This invitation link is invalid or has expired.' },
    })
    renderApp({ server, path: '/invite/accept?token=bad', routes: authTestRoutes })
    expect(await screen.findByText('This invitation link is invalid or has expired.')).toBeInTheDocument()
    expect(screen.getByText(/ask the person who invited you/i)).toBeInTheDocument()
  })

  it('checks the repeated password and shows server errors', async () => {
    const { user, server } = setup()
    await fillAndSubmit(user, 'something else entirely')
    expect(await screen.findByText("The passwords don't match.")).toBeInTheDocument()
    expect(server.callsTo('POST /invitations/accept')).toHaveLength(0)

    server.on('POST /invitations/accept', {
      status: 409,
      body: { detail: 'Conflict', errors: [{ field: 'email', message: 'This person is already a member of the workspace.' }] },
    })
    await user.clear(screen.getByLabelText('Repeat password'))
    await user.type(screen.getByLabelText('Repeat password'), 'a long enough passphrase')
    await user.click(screen.getByRole('button', { name: 'Join Acme Inc' }))
    expect(await screen.findByText('This person is already a member of the workspace.')).toBeInTheDocument()
  })
})
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd frontend && npm test -- AcceptInvitationPage`
Expected: FAIL, because the `/invite/accept` route doesn't exist yet and the heading is not found.

- [ ] **Step 3: Implement the page and route**

`frontend/src/features/auth/AcceptInvitationPage.tsx`:

```tsx
import { zodResolver } from '@hookform/resolvers/zod'
import { useQuery } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import { useForm } from 'react-hook-form'
import { Link, useNavigate, useSearchParams } from 'react-router'
import { z } from 'zod'
import { buttonVariants, Button } from '@/components/ui/button'
import { FormError } from '@/components/form/FormError'
import { TextField } from '@/components/form/TextField'
import { ListSkeleton } from '@/components/states'
import { useApi } from '@/lib/api/ApiContext'
import { ApiError } from '@/lib/api/client'
import { problemMessage } from '@/lib/api/problems'
import type { AcceptedInvitation, InvitationPreview } from '@/lib/api/types'
import { formatDateTime } from '@/lib/format'
import { AuthCard } from './AuthCard'
import { MESSAGES, newPasswordField, requiredText } from './schemas'

const schema = z
  .object({
    firstName: requiredText(80),
    lastName: requiredText(80),
    password: newPasswordField,
    repeat: z.string(),
  })
  .refine((v) => v.password === v.repeat, { path: ['repeat'], message: MESSAGES.passwordsDiffer })
type Values = z.infer<typeof schema>
const FIELDS = ['firstName', 'lastName', 'password'] as const

export function AcceptInvitationPage() {
  const api = useApi()
  const navigate = useNavigate()
  const [params] = useSearchParams()
  const [token] = useState(() => params.get('token') ?? '')
  const stripped = useRef(false)
  const [accepted, setAccepted] = useState<AcceptedInvitation | null>(null)
  const [formError, setFormError] = useState<string | null>(null)

  useEffect(() => {
    if (stripped.current) return
    stripped.current = true
    navigate('/invite/accept', { replace: true }) // keep the token out of the address bar and history
  }, [navigate])

  const preview = useQuery({
    queryKey: ['invitation-preview', token],
    enabled: token.length > 0,
    queryFn: () =>
      api.get<InvitationPreview>(`/invitations/preview?token=${encodeURIComponent(token)}`, {
        skipAuthRefresh: true,
      }),
  })

  const form = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: { firstName: '', lastName: '', password: '', repeat: '' },
  })

  const submit = form.handleSubmit(async ({ firstName, lastName, password }) => {
    setFormError(null)
    try {
      const result = await api.post<AcceptedInvitation>(
        '/invitations/accept',
        { token, firstName, lastName, password },
        { skipAuthRefresh: true },
      )
      setAccepted(result)
    } catch (error) {
      if (!applyKnownErrors(error)) setFormError(problemMessage(error))
    }
  })

  function applyKnownErrors(error: unknown): boolean {
    if (!(error instanceof ApiError) || !error.problem.errors?.length) return false
    let applied = false
    for (const { field, message } of error.problem.errors) {
      const known = FIELDS.find((f) => f === field)
      if (known) {
        form.setError(known, { type: 'server', message })
        applied = true
      } else {
        setFormError(message) // e.g. email conflict: the invitee can't change the email here
        applied = true
      }
    }
    return applied
  }

  if (!token) {
    return (
      <AuthCard title="Invitation link incomplete">
        <p className="text-sm">Ask the person who invited you to send the invitation again.</p>
      </AuthCard>
    )
  }
  if (preview.isPending) {
    return (
      <AuthCard title="Opening your invitation">
        <ListSkeleton rows={3} />
      </AuthCard>
    )
  }
  if (preview.isError) {
    return (
      <AuthCard title="This invitation can't be used">
        <p role="alert" className="text-sm">
          {problemMessage(preview.error)}
        </p>
        <p className="text-sm text-muted-foreground">
          Ask the person who invited you for a new invitation.
        </p>
      </AuthCard>
    )
  }

  const invitation = preview.data
  if (accepted) {
    const query = new URLSearchParams({ workspace: accepted.workspace, email: accepted.email })
    return (
      <AuthCard title="You're in" description={`Your account in ${invitation.workspaceName} is ready.`}>
        <Link to={`/login?${query.toString()}`} className={buttonVariants({ className: 'w-full' })}>
          Sign in to {invitation.workspaceName}
        </Link>
      </AuthCard>
    )
  }

  return (
    <AuthCard
      title={`Join ${invitation.workspaceName}`}
      description={
        <>
          You were invited as <strong>{invitation.email}</strong> with the{' '}
          <strong>{invitation.roleName}</strong> role. The invitation expires{' '}
          {formatDateTime(invitation.expiresAt)}.
        </>
      }
    >
      <form noValidate onSubmit={submit} className="space-y-4">
        <div className="grid gap-4 sm:grid-cols-2">
          <TextField form={form} name="firstName" label="First name" autoComplete="given-name" />
          <TextField form={form} name="lastName" label="Last name" autoComplete="family-name" />
        </div>
        <TextField
          form={form}
          name="password"
          label="Password"
          type="password"
          autoComplete="new-password"
          hint="At least 12 characters."
        />
        <TextField form={form} name="repeat" label="Repeat password" type="password" autoComplete="new-password" />
        <FormError message={formError} />
        <Button type="submit" className="w-full" disabled={form.formState.isSubmitting}>
          Join {invitation.workspaceName}
        </Button>
      </form>
    </AuthCard>
  )
}
```

In `frontend/src/features/auth/routes.tsx`, import `AcceptInvitationPage` and add it to `linkRoutes`:

```tsx
export const linkRoutes: RouteObject[] = [
  { path: '/verify-email', element: <VerifyEmailPage /> },
  { path: '/invite/accept', element: <AcceptInvitationPage /> },
]
```

- [ ] **Step 4: Run the tests to verify they pass, then the full gate**

Run: `cd frontend && npm test -- AcceptInvitationPage`
Expected: PASS (3 tests).

Run: `make test-frontend`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend
git commit -m "feat(frontend): accept an invitation without exposing its token in the address bar"
```

---

### Task 4: The workspace shell — navigation, user menu, overview, phase placeholders, settings frame

**Files:**
- Create: `frontend/src/features/shell/nav.ts`
- Create: `frontend/src/features/shell/AppLayout.tsx`
- Create: `frontend/src/features/shell/OverviewPage.tsx`
- Create: `frontend/src/features/shell/ComingSoonPage.tsx`
- Create: `frontend/src/features/settings/SettingsLayout.tsx`
- Create: `frontend/src/features/shell/routes.tsx`
- Modify: `frontend/src/app/router.tsx`
- Test: `frontend/src/features/shell/AppLayout.test.tsx`, `frontend/src/features/shell/ComingSoonPage.test.tsx`

**Interfaces:**
- Consumes (Task 1): `useTenantSession`, `useCan`, `PERMISSIONS`, `PermissionCode`, `NoAccess`, `PageHeader`, `EmptyState`, `useApi`, `fakeServer`, `renderApp`, `signedIn`, `testProfile`.
- Produces:
  - `appRoutes: RouteObject[]` (the `/app` subtree).
  - `settingsChildren: RouteObject[]` in `features/shell/routes.tsx`. Tasks 5–8 append their settings routes here, and Task 9 appends the audit route to `appChildren`.
  - `NAV_ITEMS`, `SETTINGS_TABS`, `firstSettingsPath(can)`.

- [ ] **Step 1: Write the failing tests**

`frontend/src/features/shell/AppLayout.test.tsx`:

```tsx
import { screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import { renderApp } from '@/test/renderApp'

function nav() {
  return within(screen.getByRole('navigation', { name: 'Workspace' }))
}

describe('AppLayout', () => {
  it('shows only enabled modules, phase features and permitted sections', async () => {
    const server = fakeServer()
    signedIn(server, testProfile({ modules: ['CRM'], permissions: ['identity.user.read'] }))
    renderApp({ server, path: '/app' })
    expect(await screen.findByRole('heading', { name: 'Welcome, Ada' })).toBeInTheDocument()
    expect(nav().getByRole('link', { name: 'Overview' })).toHaveAttribute('aria-current', 'page')
    expect(nav().getByRole('link', { name: 'CRM' })).toBeInTheDocument()
    expect(nav().queryByRole('link', { name: 'Inventory' })).not.toBeInTheDocument()
    expect(nav().getByRole('link', { name: /Workflows/ })).toBeInTheDocument()
    expect(nav().queryByRole('link', { name: 'Audit' })).not.toBeInTheDocument()
    expect(nav().getByRole('link', { name: 'Settings' })).toHaveAttribute('href', '/app/settings/users')
    expect(screen.getAllByText('Acme Inc').length).toBeGreaterThan(0)
  })

  it('hides Settings entirely without any settings permission', async () => {
    const server = fakeServer()
    signedIn(server, testProfile({ permissions: [] }))
    renderApp({ server, path: '/app' })
    await screen.findByRole('heading', { name: 'Welcome, Ada' })
    expect(nav().queryByRole('link', { name: 'Settings' })).not.toBeInTheDocument()
  })

  it('signs out everywhere', async () => {
    const server = fakeServer()
    signedIn(server).on('POST /auth/logout-all', { status: 204 })
    const { user, router } = renderApp({ server, path: '/app' })
    await user.click(await screen.findByRole('button', { name: 'Sign out everywhere' }))
    expect(server.callsTo('POST /auth/logout-all')).toHaveLength(1)
    await screen.findByRole('heading', { name: 'Sign in' })
    expect(router.state.location.pathname).toBe('/login')
  })

  it('toggles the navigation on small screens', async () => {
    const server = fakeServer()
    signedIn(server)
    const { user } = renderApp({ server, path: '/app' })
    const toggle = await screen.findByRole('button', { name: 'Menu' })
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    await user.click(toggle)
    expect(toggle).toHaveAttribute('aria-expanded', 'true')
  })

  it('opens the first settings tab the user may see', async () => {
    const server = fakeServer()
    signedIn(server, testProfile({ permissions: ['authorization.role.read'] }))
    const { router } = renderApp({ server, path: '/app/settings' })
    await screen.findByRole('navigation', { name: 'Settings' })
    expect(router.state.location.pathname).toBe('/app/settings/roles')
  })
})
```

`frontend/src/features/shell/ComingSoonPage.test.tsx`:

```tsx
import { screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import { renderApp } from '@/test/renderApp'

describe('ComingSoonPage', () => {
  it('names the blueprint phase of an enabled module', async () => {
    const server = fakeServer()
    signedIn(server, testProfile({ modules: ['CRM'] }))
    renderApp({ server, path: '/app/crm' })
    expect(await screen.findByRole('heading', { name: 'CRM' })).toBeInTheDocument()
    expect(screen.getByText(/Phase 5/)).toBeInTheDocument()
  })

  it('explains a module that is not enabled', async () => {
    const server = fakeServer()
    signedIn(server, testProfile({ modules: [] }))
    renderApp({ server, path: '/app/inventory' })
    expect(await screen.findByText('Inventory is not enabled for this workspace.')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Manage modules' })).toHaveAttribute(
      'href',
      '/app/settings/modules',
    )
  })

  it('shows platform features as coming soon', async () => {
    const server = fakeServer()
    signedIn(server)
    renderApp({ server, path: '/app/assistant' })
    expect(await screen.findByRole('heading', { name: 'AI Assistant' })).toBeInTheDocument()
    expect(screen.getByText(/Phase 12/)).toBeInTheDocument()
  })
})
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd frontend && npm test -- src/features/shell`
Expected: FAIL. `/app` has no route yet, so the "Welcome, Ada" heading is never found.

- [ ] **Step 3: Write the navigation model**

`frontend/src/features/shell/nav.ts`:

```ts
import { PERMISSIONS, type PermissionCode } from '@/features/auth/permissions'

export interface NavItem {
  to: string
  label: string
  /** Shown only when this module is enabled for the workspace (blueprint §23). */
  module?: string
  /** Shown only with ANY of these permissions. */
  anyOf?: PermissionCode[]
  /** Blueprint phase that delivers it, for "coming soon" entries. */
  phase?: number
}

export const MODULE_PHASES: Record<string, { label: string; phase: number; description: string }> = {
  CRM: { label: 'CRM', phase: 5, description: 'Customers, contacts, leads and opportunities.' },
  INVENTORY: { label: 'Inventory', phase: 6, description: 'Products, warehouses and stock movements.' },
  HELPDESK: { label: 'HelpDesk', phase: 7, description: 'Tickets, SLAs and customer support.' },
  HRMS: { label: 'HRMS', phase: 8, description: 'Employees, onboarding and leave.' },
}

export const NAV_ITEMS: NavItem[] = [
  { to: '/app', label: 'Overview' },
  { to: '/app/crm', label: 'CRM', module: 'CRM' },
  { to: '/app/inventory', label: 'Inventory', module: 'INVENTORY' },
  { to: '/app/helpdesk', label: 'HelpDesk', module: 'HELPDESK' },
  { to: '/app/hrms', label: 'HRMS', module: 'HRMS' },
  { to: '/app/workflows', label: 'Workflows', phase: 9 },
  { to: '/app/insights', label: 'Insights', phase: 11 },
  { to: '/app/assistant', label: 'AI Assistant', phase: 12 },
  { to: '/app/audit', label: 'Audit', anyOf: [PERMISSIONS.auditRead] },
]

export const SETTINGS_TABS: Array<{ to: string; label: string; anyOf: PermissionCode[] }> = [
  { to: '/app/settings/workspace', label: 'Workspace', anyOf: [PERMISSIONS.settingsRead] },
  { to: '/app/settings/users', label: 'Users', anyOf: [PERMISSIONS.userRead] },
  { to: '/app/settings/roles', label: 'Roles', anyOf: [PERMISSIONS.roleRead] },
  { to: '/app/settings/modules', label: 'Modules', anyOf: [PERMISSIONS.settingsRead] },
]

export function firstSettingsPath(can: (...codes: PermissionCode[]) => boolean): string | null {
  return SETTINGS_TABS.find((tab) => can(...tab.anyOf))?.to ?? null
}
```

- [ ] **Step 4: Write the layout, overview, placeholder page, settings frame and routes**

`frontend/src/features/shell/AppLayout.tsx`:

```tsx
import { useState } from 'react'
import { NavLink, Outlet } from 'react-router'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { useCan } from '@/features/auth/permissions'
import { useTenantSession } from '@/features/auth/tenantSession'
import { useApi } from '@/lib/api/ApiContext'
import { problemMessage } from '@/lib/api/problems'
import { fullName } from '@/lib/format'
import { cn } from '@/lib/utils'
import { firstSettingsPath, NAV_ITEMS } from './nav'

const linkClass = ({ isActive }: { isActive: boolean }) =>
  cn(
    'flex items-center justify-between rounded-md px-3 py-2 text-sm hover:bg-muted',
    isActive && 'bg-muted font-medium',
  )

export function AppLayout() {
  const session = useTenantSession()
  const api = useApi()
  const can = useCan()
  const [menuOpen, setMenuOpen] = useState(false)
  if (session.state.status !== 'authenticated') return null // RequireTenantSession guarantees this
  const { profile } = session.state
  const settingsPath = firstSettingsPath(can)
  const items = NAV_ITEMS.filter(
    (item) => (!item.module || profile.modules.includes(item.module)) && (!item.anyOf || can(...item.anyOf)),
  )

  async function signOutEverywhere() {
    try {
      await api.post('/auth/logout-all')
      session.clear()
    } catch (error) {
      toast.error(problemMessage(error))
    }
  }

  return (
    <div className="min-h-screen md:grid md:grid-cols-[15rem_1fr]">
      <header className="flex items-center justify-between border-b px-4 py-3 md:hidden">
        <span className="font-semibold">{profile.tenant.name}</span>
        <Button
          variant="outline"
          size="sm"
          aria-expanded={menuOpen}
          aria-controls="app-sidebar"
          onClick={() => setMenuOpen((open) => !open)}
        >
          Menu
        </Button>
      </header>
      <aside
        id="app-sidebar"
        className={cn('border-r bg-muted/20 p-4 md:block', menuOpen ? 'block' : 'hidden')}
      >
        <div className="mb-6 hidden md:block">
          <p className="text-xs text-muted-foreground">NexusOps</p>
          <p className="font-semibold">{profile.tenant.name}</p>
        </div>
        <nav aria-label="Workspace" className="space-y-1">
          {items.map((item) => (
            <NavLink key={item.to} to={item.to} end={item.to === '/app'} className={linkClass} onClick={() => setMenuOpen(false)}>
              <span>{item.label}</span>
              {item.phase && <span className="text-xs text-muted-foreground">Soon</span>}
            </NavLink>
          ))}
          {settingsPath && (
            <NavLink to={settingsPath} className={linkClass} onClick={() => setMenuOpen(false)}>
              Settings
            </NavLink>
          )}
        </nav>
        <div className="mt-8 space-y-2 border-t pt-4 text-sm">
          <p className="font-medium">{fullName(profile.user)}</p>
          <p className="truncate text-xs text-muted-foreground">{profile.user.email}</p>
          <div className="flex flex-col gap-2 pt-2">
            <Button variant="outline" size="sm" onClick={() => void session.logout()}>
              Sign out
            </Button>
            <Button variant="ghost" size="sm" onClick={() => void signOutEverywhere()}>
              Sign out everywhere
            </Button>
          </div>
        </div>
      </aside>
      <main className="min-w-0 p-4 md:p-8">
        <Outlet />
      </main>
    </div>
  )
}
```

The NavLink text for coming-soon items is "Workflows" plus "Soon", so tests match them with `/Workflows/`.

`frontend/src/features/shell/OverviewPage.tsx`:

```tsx
import { Link } from 'react-router'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { PageHeader } from '@/components/states'
import { PERMISSIONS, useCan } from '@/features/auth/permissions'
import { useTenantSession } from '@/features/auth/tenantSession'
import { MODULE_PHASES } from './nav'

/** A placeholder dashboard (blueprint §23): real widgets arrive with the modules and Insights (Phase 11). */
export function OverviewPage() {
  const session = useTenantSession()
  const can = useCan()
  if (session.state.status !== 'authenticated') return null
  const { profile } = session.state
  const enabled = profile.modules.map((code) => MODULE_PHASES[code]?.label ?? code)

  return (
    <>
      <PageHeader title={`Welcome, ${profile.user.firstName}`} description={`${profile.tenant.name} · ${profile.tenant.planCode} plan`} />
      <div className="grid gap-4 md:grid-cols-2">
        <Card>
          <CardHeader>
            <CardTitle>Modules</CardTitle>
          </CardHeader>
          <CardContent className="text-sm">
            {enabled.length ? enabled.join(', ') : 'No modules enabled yet.'}{' '}
            {can(PERMISSIONS.settingsRead) && (
              <Link to="/app/settings/modules" className="underline">
                Manage modules
              </Link>
            )}
          </CardContent>
        </Card>
        <Card>
          <CardHeader>
            <CardTitle>Get started</CardTitle>
          </CardHeader>
          <CardContent className="space-y-1 text-sm">
            {can(PERMISSIONS.userRead) && (
              <p>
                <Link to="/app/settings/users" className="underline">Invite your team</Link>
              </p>
            )}
            {can(PERMISSIONS.roleRead) && (
              <p>
                <Link to="/app/settings/roles" className="underline">Design roles and permissions</Link>
              </p>
            )}
            {can(PERMISSIONS.auditRead) && (
              <p>
                <Link to="/app/audit" className="underline">Review the audit log</Link>
              </p>
            )}
            <p className="text-muted-foreground">Dashboards arrive with Insights (Phase 11).</p>
          </CardContent>
        </Card>
      </div>
    </>
  )
}
```

`frontend/src/features/shell/ComingSoonPage.tsx`:

```tsx
import { Link } from 'react-router'
import { EmptyState, PageHeader } from '@/components/states'
import { buttonVariants } from '@/components/ui/button'
import { PERMISSIONS, useCan } from '@/features/auth/permissions'
import { useTenantSession } from '@/features/auth/tenantSession'

export function ComingSoonPage({
  title,
  phase,
  description,
  module,
}: {
  title: string
  phase: number
  description: string
  module?: string
}) {
  const session = useTenantSession()
  const can = useCan()
  const modules = session.state.status === 'authenticated' ? session.state.profile.modules : []

  if (module && !modules.includes(module)) {
    return (
      <>
        <PageHeader title={title} />
        <EmptyState
          title={`${title} is not enabled for this workspace.`}
          description="An owner or admin can enable it under Settings → Modules."
          action={
            can(PERMISSIONS.settingsRead) ? (
              <Link to="/app/settings/modules" className={buttonVariants({ variant: 'outline' })}>
                Manage modules
              </Link>
            ) : undefined
          }
        />
      </>
    )
  }
  return (
    <>
      <PageHeader title={title} description={description} />
      <EmptyState
        title="Coming soon"
        description={`${title} is delivered in Phase ${phase} of the NexusOps roadmap.`}
      />
    </>
  )
}
```

`frontend/src/features/settings/SettingsLayout.tsx`:

```tsx
import { Navigate, NavLink, Outlet, useLocation } from 'react-router'
import { NoAccess } from '@/components/states'
import { useCan } from '@/features/auth/permissions'
import { firstSettingsPath, SETTINGS_TABS } from '@/features/shell/nav'
import { cn } from '@/lib/utils'

export function SettingsLayout() {
  const can = useCan()
  const location = useLocation()
  const tabs = SETTINGS_TABS.filter((tab) => can(...tab.anyOf))
  const first = firstSettingsPath(can)
  if (!first) return <NoAccess />
  if (location.pathname.replace(/\/$/, '') === '/app/settings') return <Navigate to={first} replace />
  return (
    <div className="space-y-6">
      <nav aria-label="Settings" className="flex flex-wrap gap-2 border-b pb-2">
        {tabs.map((tab) => (
          <NavLink
            key={tab.to}
            to={tab.to}
            className={({ isActive }) =>
              cn('rounded-md px-3 py-1.5 text-sm hover:bg-muted', isActive && 'bg-muted font-medium')
            }
          >
            {tab.label}
          </NavLink>
        ))}
      </nav>
      <Outlet />
    </div>
  )
}
```

`frontend/src/features/shell/routes.tsx`. The settings child routes and the audit route are added by Tasks 5–9.

```tsx
import type { RouteObject } from 'react-router'
import { EmptyState } from '@/components/states'
import { SettingsLayout } from '@/features/settings/SettingsLayout'
import { AppLayout } from './AppLayout'
import { ComingSoonPage } from './ComingSoonPage'
import { MODULE_PHASES } from './nav'
import { OverviewPage } from './OverviewPage'

function modulePage(path: string, code: string): RouteObject {
  const info = MODULE_PHASES[code]
  return {
    path,
    element: <ComingSoonPage title={info.label} phase={info.phase} description={info.description} module={code} />,
  }
}

/** /app/settings/* children (Tasks 5–8 add theirs before the catch-all, which must stay last). */
export const settingsChildren: RouteObject[] = [
  {
    path: '*',
    element: <EmptyState title="Settings page not found" description="Pick a section above." />,
  },
]

/** /app/* children (Task 9 appends the audit route). */
export const appChildren: RouteObject[] = [
  { index: true, element: <OverviewPage /> },
  modulePage('crm', 'CRM'),
  modulePage('inventory', 'INVENTORY'),
  modulePage('helpdesk', 'HELPDESK'),
  modulePage('hrms', 'HRMS'),
  {
    path: 'workflows',
    element: <ComingSoonPage title="Workflows" phase={9} description="Visual, auditable business workflows." />,
  },
  {
    path: 'insights',
    element: <ComingSoonPage title="Insights" phase={11} description="Operational dashboards across modules." />,
  },
  {
    path: 'assistant',
    element: <ComingSoonPage title="AI Assistant" phase={12} description="Permission-aware answers about your workspace." />,
  },
  { path: 'settings', element: <SettingsLayout />, children: settingsChildren },
]

export const appRoutes: RouteObject[] = [{ path: '/app', element: <AppLayout />, children: appChildren }]
```

In `frontend/src/app/router.tsx`, import `appRoutes` and set the `RequireTenantSession` children to `appRoutes`, replacing the Task 1 comment.

Tasks 5–8 insert their settings routes into the `settingsChildren` array literal, *before* the `*` catch-all. Task 9 inserts the audit route into `appChildren`. Each of those tasks edits only `features/shell/routes.tsx`. The catch-all also means `/app/settings` redirects work before the tabs exist.

- [ ] **Step 5: Run the tests to verify they pass, then the full gate**

Run: `cd frontend && npm test`
Expected: PASS (all suites, including Tasks 1–3).

Run: `make test-frontend`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add frontend
git commit -m "feat(frontend): workspace shell with module- and permission-aware navigation"
```

---
### Task 5: Settings — Workspace and Modules

**Files:**
- Create: `frontend/src/features/settings/WorkspaceSettingsPage.tsx`
- Create: `frontend/src/features/settings/ModulesSettingsPage.tsx`
- Modify: `frontend/src/features/shell/routes.tsx` (add `workspace` and `modules` to `settingsChildren`, before `*`)
- Test: `frontend/src/features/settings/WorkspaceSettingsPage.test.tsx`, `frontend/src/features/settings/ModulesSettingsPage.test.tsx`

**Interfaces:**
- Consumes:
  - Task 1: `useApi`, `useCan`, `PERMISSIONS`, `RequirePermission`, `useTenantSession`, `TenantSettings`, `ModuleState`, `applyFieldErrors`, `problemMessage`, `Field`, `describedBy`, `NativeSelect`, `Checkbox`, `PageHeader`, `ListSkeleton`, `ErrorState`, `EmptyState`.
  - Task 2: `TextField`, `FormError`, `requiredText`.
  - Task 4: `MODULE_PHASES`, `settingsChildren`.
- Produces: routes `/app/settings/workspace` and `/app/settings/modules`.

- [ ] **Step 1: Write the failing tests**

`frontend/src/features/settings/WorkspaceSettingsPage.test.tsx`:

```tsx
import { screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import { renderApp } from '@/test/renderApp'

const SETTINGS = {
  id: 't-acme',
  slug: 'acme',
  name: 'Acme Inc',
  status: 'ACTIVE',
  planCode: 'FREE',
  timezone: 'UTC',
  locale: 'en',
  currency: 'USD',
}

describe('WorkspaceSettingsPage', () => {
  it('saves changed settings and refreshes the profile', async () => {
    const server = fakeServer()
    signedIn(server)
      .on('GET /tenant', { body: SETTINGS })
      .on('PATCH /tenant', (req) => ({ body: { ...SETTINGS, ...(req.body as object) } }))
    const { user } = renderApp({ server, path: '/app/settings/workspace' })
    const name = await screen.findByLabelText('Workspace name')
    expect(name).toHaveValue('Acme Inc')
    expect(screen.getByText('acme')).toBeInTheDocument()
    await user.clear(name)
    await user.type(name, 'Acme Group')
    await user.clear(screen.getByLabelText('Currency'))
    await user.type(screen.getByLabelText('Currency'), 'inr')
    await user.click(screen.getByRole('button', { name: 'Save changes' }))
    expect(await screen.findByText('Workspace settings saved.')).toBeInTheDocument()
    expect(server.callsTo('PATCH /tenant')[0].body).toEqual({
      name: 'Acme Group',
      timezone: 'UTC',
      locale: 'en',
      currency: 'INR',
    })
    expect(server.callsTo('GET /me').length).toBeGreaterThan(1)
  })

  it('shows a server field error', async () => {
    const server = fakeServer()
    signedIn(server)
      .on('GET /tenant', { body: SETTINGS })
      .on('PATCH /tenant', {
        status: 400,
        body: { detail: 'Bad Request', errors: [{ field: 'locale', message: 'Use a language tag such as en or en-IN.' }] },
      })
    const { user } = renderApp({ server, path: '/app/settings/workspace' })
    await user.clear(await screen.findByLabelText('Locale'))
    await user.type(screen.getByLabelText('Locale'), '!!')
    await user.click(screen.getByRole('button', { name: 'Save changes' }))
    expect(await screen.findByText('Use a language tag such as en or en-IN.')).toBeInTheDocument()
  })

  it('is read-only without the update permission', async () => {
    const server = fakeServer()
    signedIn(server, testProfile({ permissions: ['tenant.settings.read'] })).on('GET /tenant', { body: SETTINGS })
    renderApp({ server, path: '/app/settings/workspace' })
    expect(await screen.findByLabelText('Workspace name')).toBeDisabled()
    expect(screen.queryByRole('button', { name: 'Save changes' })).not.toBeInTheDocument()
    expect(screen.getByText(/can view these settings but not change them/i)).toBeInTheDocument()
  })

  it('shows an error state with a retry', async () => {
    const server = fakeServer()
    signedIn(server).on('GET /tenant', { status: 500, body: { detail: 'Boom.', requestId: 'req-1' } })
    renderApp({ server, path: '/app/settings/workspace' })
    expect(await screen.findByText('Boom.')).toBeInTheDocument()
    expect(screen.getByText('Reference: req-1')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument()
  })
})
```

`frontend/src/features/settings/ModulesSettingsPage.test.tsx`:

```tsx
import { screen, waitFor } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import { renderApp } from '@/test/renderApp'

const MODULES = [
  { code: 'CRM', name: 'CRM', enabled: true },
  { code: 'INVENTORY', name: 'Inventory', enabled: false },
]

describe('ModulesSettingsPage', () => {
  it('enables a module', async () => {
    const server = fakeServer()
    signedIn(server)
      .on('GET /tenant/modules', { body: MODULES })
      .on('PUT /tenant/modules/:code', (req) => ({
        body: { code: req.params.code, name: 'Inventory', enabled: (req.body as { enabled: boolean }).enabled },
      }))
    const { user } = renderApp({ server, path: '/app/settings/modules' })
    const toggle = await screen.findByRole('switch', { name: 'Inventory' })
    expect(toggle).not.toBeChecked()
    await user.click(toggle)
    expect(await screen.findByText('Inventory enabled.')).toBeInTheDocument()
    expect(server.callsTo('PUT /tenant/modules/:code')[0]).toMatchObject({
      params: { code: 'INVENTORY' },
      body: { enabled: true },
    })
    await waitFor(() => expect(screen.getByRole('switch', { name: 'Inventory' })).toBeChecked())
  })

  it('reports the plan limit and leaves the switch off', async () => {
    const server = fakeServer()
    signedIn(server)
      .on('GET /tenant/modules', { body: MODULES })
      .on('PUT /tenant/modules/:code', {
        status: 409,
        body: { detail: 'Your plan allows 2 modules. Upgrade to enable more.' },
      })
    const { user } = renderApp({ server, path: '/app/settings/modules' })
    await user.click(await screen.findByRole('switch', { name: 'Inventory' }))
    expect(await screen.findByText('Your plan allows 2 modules. Upgrade to enable more.')).toBeInTheDocument()
    expect(screen.getByRole('switch', { name: 'Inventory' })).not.toBeChecked()
  })

  it('is read-only without the manage permission', async () => {
    const server = fakeServer()
    signedIn(server, testProfile({ permissions: ['tenant.settings.read'] })).on('GET /tenant/modules', {
      body: MODULES,
    })
    renderApp({ server, path: '/app/settings/modules' })
    expect(await screen.findByRole('switch', { name: 'CRM' })).toBeDisabled()
  })
})
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd frontend && npm test -- src/features/settings`
Expected: FAIL. No route exists yet, so the catch-all "Settings page not found" renders and the labels are not found.

- [ ] **Step 3: Implement the two pages**

`frontend/src/features/settings/WorkspaceSettingsPage.tsx`:

```tsx
import { zodResolver } from '@hookform/resolvers/zod'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useMemo, useState } from 'react'
import { useForm } from 'react-hook-form'
import { toast } from 'sonner'
import { z } from 'zod'
import { Button } from '@/components/ui/button'
import { describedBy, Field } from '@/components/form/Field'
import { FormError } from '@/components/form/FormError'
import { NativeSelect } from '@/components/form/NativeSelect'
import { TextField } from '@/components/form/TextField'
import { ErrorState, ListSkeleton, PageHeader } from '@/components/states'
import { requiredText } from '@/features/auth/schemas'
import { PERMISSIONS, useCan } from '@/features/auth/permissions'
import { useTenantSession } from '@/features/auth/tenantSession'
import { useApi } from '@/lib/api/ApiContext'
import { applyFieldErrors, problemMessage } from '@/lib/api/problems'
import type { TenantSettings } from '@/lib/api/types'

const schema = z.object({
  name: requiredText(120),
  timezone: requiredText(64),
  locale: requiredText(35),
  currency: z
    .string()
    .trim()
    .regex(/^[A-Za-z]{3}$/, 'Use a 3-letter ISO currency code such as USD.'),
})
type Values = z.infer<typeof schema>
const FIELDS = ['name', 'timezone', 'locale', 'currency'] as const

export function WorkspaceSettingsPage() {
  const api = useApi()
  const settings = useQuery({ queryKey: ['tenant'], queryFn: () => api.get<TenantSettings>('/tenant') })
  return (
    <>
      <PageHeader title="Workspace" description="Name, regional defaults and plan." />
      {settings.isPending ? (
        <ListSkeleton rows={4} />
      ) : settings.isError ? (
        <ErrorState error={settings.error} onRetry={() => void settings.refetch()} />
      ) : (
        <WorkspaceForm settings={settings.data} />
      )}
    </>
  )
}

function WorkspaceForm({ settings }: { settings: TenantSettings }) {
  const api = useApi()
  const queryClient = useQueryClient()
  const session = useTenantSession()
  const canEdit = useCan()(PERMISSIONS.settingsUpdate)
  const [formError, setFormError] = useState<string | null>(null)
  const form = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: {
      name: settings.name,
      timezone: settings.timezone,
      locale: settings.locale,
      currency: settings.currency,
    },
  })
  const zones = useMemo(() => {
    const all = new Set(Intl.supportedValuesOf('timeZone'))
    all.add('UTC')
    all.add(settings.timezone)
    return [...all].sort()
  }, [settings.timezone])

  const submit = form.handleSubmit(async (values) => {
    setFormError(null)
    try {
      const updated = await api.patch<TenantSettings>('/tenant', {
        ...values,
        currency: values.currency.toUpperCase(),
      })
      queryClient.setQueryData(['tenant'], updated)
      form.reset({ name: updated.name, timezone: updated.timezone, locale: updated.locale, currency: updated.currency })
      toast.success('Workspace settings saved.')
      await session.reloadProfile()
    } catch (error) {
      if (!applyFieldErrors(error, form.setError, FIELDS)) setFormError(problemMessage(error))
    }
  })

  const timezoneError = form.formState.errors.timezone?.message
  return (
    <form noValidate onSubmit={submit} className="max-w-xl space-y-6">
      <dl className="grid grid-cols-3 gap-2 rounded-lg border p-4 text-sm">
        <dt className="text-muted-foreground">Workspace URL</dt>
        <dd className="col-span-2">{settings.slug}</dd>
        <dt className="text-muted-foreground">Plan</dt>
        <dd className="col-span-2">{settings.planCode}</dd>
        <dt className="text-muted-foreground">Status</dt>
        <dd className="col-span-2">{settings.status}</dd>
      </dl>
      {!canEdit && (
        <p className="text-sm text-muted-foreground">
          You can view these settings but not change them.
        </p>
      )}
      <fieldset disabled={!canEdit} className="space-y-4">
        <TextField form={form} name="name" label="Workspace name" />
        <Field id="field-timezone" label="Time zone" error={timezoneError}>
          <NativeSelect
            id="field-timezone"
            aria-invalid={timezoneError ? true : undefined}
            aria-describedby={describedBy('field-timezone', timezoneError)}
            {...form.register('timezone')}
          >
            {zones.map((zone) => (
              <option key={zone} value={zone}>
                {zone}
              </option>
            ))}
          </NativeSelect>
        </Field>
        <TextField form={form} name="locale" label="Locale" hint="A language tag such as en or en-IN." />
        <TextField form={form} name="currency" label="Currency" hint="ISO 4217, e.g. USD or INR." maxLength={3} />
      </fieldset>
      <FormError message={formError} />
      {canEdit && (
        <Button type="submit" disabled={form.formState.isSubmitting}>
          Save changes
        </Button>
      )}
    </form>
  )
}
```

`frontend/src/features/settings/ModulesSettingsPage.tsx`:

```tsx
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { Checkbox } from '@/components/form/Checkbox'
import { EmptyState, ErrorState, ListSkeleton, PageHeader } from '@/components/states'
import { PERMISSIONS, useCan } from '@/features/auth/permissions'
import { useTenantSession } from '@/features/auth/tenantSession'
import { MODULE_PHASES } from '@/features/shell/nav'
import { useApi } from '@/lib/api/ApiContext'
import { problemMessage } from '@/lib/api/problems'
import type { ModuleState } from '@/lib/api/types'

export function ModulesSettingsPage() {
  const api = useApi()
  const queryClient = useQueryClient()
  const session = useTenantSession()
  const canManage = useCan()(PERMISSIONS.modulesManage)
  const plan = session.state.status === 'authenticated' ? session.state.profile.tenant.planCode : ''
  const modules = useQuery({
    queryKey: ['tenant-modules'],
    queryFn: () => api.get<ModuleState[]>('/tenant/modules'),
  })
  const toggle = useMutation({
    mutationFn: ({ code, enabled }: { code: string; enabled: boolean }) =>
      api.put<ModuleState>(`/tenant/modules/${encodeURIComponent(code)}`, { enabled }),
    onSuccess: (updated) => {
      queryClient.setQueryData<ModuleState[]>(['tenant-modules'], (old) =>
        old?.map((m) => (m.code === updated.code ? updated : m)),
      )
      toast.success(`${updated.name} ${updated.enabled ? 'enabled' : 'disabled'}.`)
      void session.reloadProfile() // the navigation shows enabled modules only
    },
    onError: (error) => toast.error(problemMessage(error)),
  })

  return (
    <>
      <PageHeader
        title="Modules"
        description={`Turn business modules on or off. Your ${plan} plan limits how many can be on at once.`}
      />
      {modules.isPending ? (
        <ListSkeleton rows={4} />
      ) : modules.isError ? (
        <ErrorState error={modules.error} onRetry={() => void modules.refetch()} />
      ) : modules.data.length === 0 ? (
        <EmptyState title="No modules available" description="Modules will appear here as they ship." />
      ) : (
        <ul className="divide-y rounded-lg border">
          {modules.data.map((module) => {
            const id = `module-${module.code}`
            return (
              <li key={module.code} className="flex items-center justify-between gap-4 p-4">
                <div>
                  <label htmlFor={id} className="font-medium">
                    {module.name}
                  </label>
                  <p id={`${id}-desc`} className="text-sm text-muted-foreground">
                    {MODULE_PHASES[module.code]?.description ?? ''}
                  </p>
                </div>
                <Checkbox
                  id={id}
                  role="switch"
                  aria-describedby={`${id}-desc`}
                  checked={module.enabled}
                  disabled={!canManage || toggle.isPending}
                  onChange={(event) => toggle.mutate({ code: module.code, enabled: event.target.checked })}
                />
              </li>
            )
          })}
        </ul>
      )}
    </>
  )
}
```

In `frontend/src/features/shell/routes.tsx`, insert these at the start of the `settingsChildren` array, before the `*` route:

```tsx
  {
    path: 'workspace',
    element: (
      <RequirePermission anyOf={[PERMISSIONS.settingsRead]}>
        <WorkspaceSettingsPage />
      </RequirePermission>
    ),
  },
  {
    path: 'modules',
    element: (
      <RequirePermission anyOf={[PERMISSIONS.settingsRead]}>
        <ModulesSettingsPage />
      </RequirePermission>
    ),
  },
```

Add the imports for `PERMISSIONS`, `RequirePermission`, `WorkspaceSettingsPage` and `ModulesSettingsPage`.

- [ ] **Step 4: Run the tests to verify they pass, then the full gate**

Run: `cd frontend && npm test -- src/features/settings`
Expected: PASS (7 tests).

Run: `make test-frontend`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend
git commit -m "feat(frontend): workspace settings and module toggles"
```

---

### Task 6: Settings — Users (list, filters, paging, rename, disable/enable, roles)

**Files:**
- Create: `frontend/src/lib/query.ts`
- Create: `frontend/src/components/Pagination.tsx`
- Create: `frontend/src/components/StatusBadge.tsx`
- Create: `frontend/src/features/settings/users/UsersPage.tsx`
- Create: `frontend/src/features/settings/users/UserDialog.tsx`
- Modify: `frontend/src/features/shell/routes.tsx` (add `users` before `*`)
- Test: `frontend/src/features/settings/users/UsersPage.test.tsx`

**Interfaces:**
- Consumes:
  - Task 1: `useApi`, `useCan`, `PERMISSIONS`, `RequirePermission`, `useTenantSession`, `Page`, `UserView`, `RoleView`, `problemMessage`, `applyFieldErrors`, the states, `Checkbox`, `NativeSelect`, `fullName`, `formatDateTime`.
  - Task 2: `TextField`, `FormError`, `requiredText`.
  - The shadcn `Table*`, `Dialog*`, `Badge`, `Input`, `Button`.
- Produces:
  - `toQuery(params)` (from `lib/query.ts`).
  - `Pagination({ page, size, total, onPage })`.
  - `StatusBadge({ status })`, covering user, invitation and tenant statuses.
  - `UsersPage`, which renders a `{/* invitations */}` slot that Task 7 fills.

- [ ] **Step 1: Write the failing test**

`frontend/src/features/settings/users/UsersPage.test.tsx`:

```tsx
import { screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile, user as aUser } from '@/test/fixtures'
import { renderApp } from '@/test/renderApp'

const GRACE = aUser({
  id: 'u-grace',
  email: 'grace@acme.test',
  firstName: 'Grace',
  lastName: 'Hopper',
  roles: [{ id: 'r-support', name: 'Support' }],
})
const ROLES = [
  { id: 'r-owner', name: 'TENANT_OWNER', description: null, system: true, permissions: [] },
  { id: 'r-support', name: 'Support', description: null, system: false, permissions: ['identity.user.read'] },
  { id: 'r-auditor', name: 'Auditor', description: null, system: false, permissions: ['audit.event.read'] },
]

function page(items = [aUser(), GRACE], total = items.length) {
  return { items, page: 0, size: 20, total }
}

function setup(profile = testProfile()) {
  const server = fakeServer()
  signedIn(server, profile)
    .on('GET /users', { body: page() })
    .on('GET /roles', { body: ROLES })
    .on('GET /invitations', { body: [] })
  return { server, ...renderApp({ server, path: '/app/settings/users' }) }
}

describe('UsersPage', () => {
  it('lists people with their roles and status', async () => {
    const { server } = setup()
    const row = (await screen.findByText('grace@acme.test')).closest('tr')
    expect(row).not.toBeNull()
    expect(within(row as HTMLElement).getByText('Support')).toBeInTheDocument()
    expect(within(row as HTMLElement).getByText('Active')).toBeInTheDocument()
    const query = server.callsTo('GET /users')[0].query
    expect(query.get('page')).toBe('0')
    expect(query.get('size')).toBe('20')
  })

  it('filters by status and search, and pages', async () => {
    const { server, user } = setup()
    server.on('GET /users', (req) => ({ body: { ...page(), total: 45, page: Number(req.query.get('page')) } }))
    await screen.findByText('grace@acme.test')
    await user.selectOptions(screen.getByLabelText('Status'), 'DISABLED')
    await user.type(screen.getByLabelText('Search people'), 'grace')
    await user.click(screen.getByRole('button', { name: 'Search' }))
    await user.click(await screen.findByRole('button', { name: 'Next page' }))
    const last = server.callsTo('GET /users').at(-1)?.query
    expect(last?.get('status')).toBe('DISABLED')
    expect(last?.get('q')).toBe('grace')
    expect(last?.get('page')).toBe('1')
    expect(screen.getByText('Page 2 of 3')).toBeInTheDocument()
  })

  it('shows an empty state', async () => {
    const { server } = setup()
    server.on('GET /users', { body: page([], 0) })
    expect(await screen.findByText('No people match these filters.')).toBeInTheDocument()
  })

  it('renames a person', async () => {
    const { server, user } = setup()
    server.on('PATCH /users/:id', (req) => ({ body: { ...GRACE, ...(req.body as object) } }))
    await user.click(await screen.findByRole('button', { name: 'Manage Grace Hopper' }))
    const dialog = await screen.findByRole('dialog')
    await user.clear(within(dialog).getByLabelText('First name'))
    await user.type(within(dialog).getByLabelText('First name'), 'Rear Admiral')
    await user.click(within(dialog).getByRole('button', { name: 'Save name' }))
    expect(await screen.findByText('Name updated.')).toBeInTheDocument()
    expect(server.callsTo('PATCH /users/:id')[0]).toMatchObject({
      params: { id: 'u-grace' },
      body: { firstName: 'Rear Admiral', lastName: 'Hopper' },
    })
  })

  it("shows the server's 403 detail when disabling a stronger user", async () => {
    const { server, user } = setup()
    server.on('PATCH /users/:id', {
      status: 403,
      body: { detail: "You can't manage a user with permissions you don't have." },
    })
    await user.click(await screen.findByRole('button', { name: 'Manage Grace Hopper' }))
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('button', { name: 'Disable user' }))
    await user.click(within(dialog).getByRole('button', { name: 'Confirm disable' }))
    expect(
      await within(dialog).findByText("You can't manage a user with permissions you don't have."),
    ).toBeInTheDocument()
    expect(server.callsTo('PATCH /users/:id')[0].body).toEqual({ status: 'DISABLED' })
  })

  it("changes a person's roles", async () => {
    const { server, user } = setup()
    server.on('PUT /users/:id/roles', (req) => ({
      body: { ...GRACE, roles: [{ id: 'r-support', name: 'Support' }, { id: 'r-auditor', name: 'Auditor' }], _echo: req.body },
    }))
    await user.click(await screen.findByRole('button', { name: 'Manage Grace Hopper' }))
    const dialog = await screen.findByRole('dialog')
    expect(await within(dialog).findByRole('checkbox', { name: 'Support' })).toBeChecked()
    await user.click(within(dialog).getByRole('checkbox', { name: 'Auditor' }))
    await user.click(within(dialog).getByRole('button', { name: 'Save roles' }))
    expect(await screen.findByText('Roles updated.')).toBeInTheDocument()
    expect(server.callsTo('PUT /users/:id/roles')[0].body).toEqual({ roleIds: ['r-support', 'r-auditor'] })
  })

  it('offers no management actions to a read-only viewer', async () => {
    setup(testProfile({ permissions: ['identity.user.read'] }))
    await screen.findByText('grace@acme.test')
    expect(screen.queryByRole('button', { name: /^Manage / })).not.toBeInTheDocument()
  })

  it('cannot disable your own account from the dialog', async () => {
    const { user } = setup()
    await user.click(await screen.findByRole('button', { name: 'Manage Ada Lovelace' }))
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).queryByRole('button', { name: 'Disable user' })).not.toBeInTheDocument()
  })
})
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd frontend && npm test -- UsersPage`
Expected: FAIL (no `users` route, so "Settings page not found" renders).

- [ ] **Step 3: Implement the helpers**

`frontend/src/lib/query.ts`:

```ts
/** Builds a query string from defined, non-empty values (never sends `status=` for "all"). */
export function toQuery(params: Record<string, string | number | null | undefined>): string {
  const search = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== null && value !== '') search.set(key, String(value))
  }
  return search.toString()
}
```

`frontend/src/components/Pagination.tsx`:

```tsx
import { Button } from '@/components/ui/button'

export function Pagination({
  page,
  size,
  total,
  onPage,
}: {
  page: number
  size: number
  total: number
  onPage: (page: number) => void
}) {
  const pages = Math.max(1, Math.ceil(total / size))
  if (total <= size && page === 0) return null
  return (
    <nav aria-label="Pagination" className="mt-4 flex items-center justify-between text-sm">
      <span>
        Page {page + 1} of {pages}
      </span>
      <div className="flex gap-2">
        <Button variant="outline" size="sm" disabled={page === 0} onClick={() => onPage(page - 1)} aria-label="Previous page">
          Previous
        </Button>
        <Button variant="outline" size="sm" disabled={page + 1 >= pages} onClick={() => onPage(page + 1)} aria-label="Next page">
          Next
        </Button>
      </div>
    </nav>
  )
}
```

`frontend/src/components/StatusBadge.tsx`:

```tsx
import { Badge } from '@/components/ui/badge'

const LABELS: Record<string, { label: string; variant: 'default' | 'secondary' | 'destructive' | 'outline' }> = {
  ACTIVE: { label: 'Active', variant: 'default' },
  INVITED: { label: 'Invited', variant: 'secondary' },
  DISABLED: { label: 'Disabled', variant: 'destructive' },
  PENDING: { label: 'Pending', variant: 'secondary' },
  ACCEPTED: { label: 'Accepted', variant: 'outline' },
  REVOKED: { label: 'Revoked', variant: 'outline' },
  EXPIRED: { label: 'Expired', variant: 'outline' },
  SUSPENDED: { label: 'Suspended', variant: 'destructive' },
  PENDING_VERIFICATION: { label: 'Pending verification', variant: 'secondary' },
}

export function StatusBadge({ status }: { status: string }) {
  const known = LABELS[status] ?? { label: status, variant: 'outline' as const }
  return <Badge variant={known.variant}>{known.label}</Badge>
}
```

If the generated `badge.tsx` doesn't accept one of these variant names, map that status to `outline` and note it in the report.

- [ ] **Step 4: Implement the page and the dialog**

`frontend/src/features/settings/users/UsersPage.tsx`:

```tsx
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { useSearchParams } from 'react-router'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { NativeSelect } from '@/components/form/NativeSelect'
import { Pagination } from '@/components/Pagination'
import { StatusBadge } from '@/components/StatusBadge'
import { EmptyState, ErrorState, ListSkeleton, PageHeader } from '@/components/states'
import { PERMISSIONS, useCan } from '@/features/auth/permissions'
import { useApi } from '@/lib/api/ApiContext'
import type { Page, UserView } from '@/lib/api/types'
import { formatDateTime, fullName } from '@/lib/format'
import { toQuery } from '@/lib/query'
import { UserDialog } from './UserDialog'

const SIZE = 20

export function UsersPage() {
  const api = useApi()
  const can = useCan()
  const [params, setParams] = useSearchParams()
  const q = params.get('q') ?? ''
  const status = params.get('status') ?? ''
  const page = Math.max(0, Number(params.get('page') ?? '0') || 0)
  const [selected, setSelected] = useState<UserView | null>(null)
  const canManage = can(PERMISSIONS.userUpdate, PERMISSIONS.userDisable, PERMISSIONS.roleAssign)

  const users = useQuery({
    queryKey: ['users', { q, status, page }],
    queryFn: () => api.get<Page<UserView>>(`/users?${toQuery({ q, status, page, size: SIZE })}`),
    placeholderData: keepPreviousData,
  })

  function update(next: Record<string, string>) {
    const merged = new URLSearchParams(params)
    for (const [key, value] of Object.entries(next)) {
      if (value) merged.set(key, value)
      else merged.delete(key)
    }
    setParams(merged, { replace: true })
  }

  function onSearch(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const value = new FormData(event.currentTarget).get('q')
    update({ q: typeof value === 'string' ? value.trim() : '', page: '' })
  }

  return (
    <>
      <PageHeader title="Users" description="People in this workspace and what they can do." />
      <div className="mb-4 flex flex-wrap items-end gap-3">
        <form onSubmit={onSearch} className="flex items-end gap-2" role="search">
          <div className="space-y-1.5">
            <Label htmlFor="users-q">Search people</Label>
            <Input id="users-q" name="q" defaultValue={q} placeholder="Name or email" />
          </div>
          <Button type="submit" variant="outline">
            Search
          </Button>
        </form>
        <div className="space-y-1.5">
          <Label htmlFor="users-status">Status</Label>
          <NativeSelect id="users-status" value={status} onChange={(e) => update({ status: e.target.value, page: '' })}>
            <option value="">All statuses</option>
            <option value="ACTIVE">Active</option>
            <option value="INVITED">Invited</option>
            <option value="DISABLED">Disabled</option>
          </NativeSelect>
        </div>
      </div>

      {users.isPending ? (
        <ListSkeleton />
      ) : users.isError ? (
        <ErrorState error={users.error} onRetry={() => void users.refetch()} />
      ) : users.data.items.length === 0 ? (
        <EmptyState title="No people match these filters." description="Try a different search or status." />
      ) : (
        <>
          <div className="overflow-x-auto rounded-lg border">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Person</TableHead>
                  <TableHead>Roles</TableHead>
                  <TableHead>Status</TableHead>
                  <TableHead>Last sign-in</TableHead>
                  {canManage && <TableHead><span className="sr-only">Actions</span></TableHead>}
                </TableRow>
              </TableHeader>
              <TableBody>
                {users.data.items.map((person) => (
                  <TableRow key={person.id}>
                    <TableCell>
                      <div className="font-medium">{fullName(person)}</div>
                      <div className="text-xs text-muted-foreground">{person.email}</div>
                    </TableCell>
                    <TableCell>
                      <div className="flex flex-wrap gap-1">
                        {person.roles.map((role) => (
                          <Badge key={role.id} variant="outline">
                            {role.name}
                          </Badge>
                        ))}
                      </div>
                    </TableCell>
                    <TableCell>
                      <StatusBadge status={person.status} />
                    </TableCell>
                    <TableCell>{formatDateTime(person.lastLoginAt)}</TableCell>
                    {canManage && (
                      <TableCell className="text-right">
                        <Button variant="outline" size="sm" aria-label={`Manage ${fullName(person)}`} onClick={() => setSelected(person)}>
                          Manage
                        </Button>
                      </TableCell>
                    )}
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
          <Pagination page={users.data.page} size={users.data.size} total={users.data.total} onPage={(p) => update({ page: String(p) })} />
        </>
      )}

      {/* invitations: Task 7 */}

      {selected && <UserDialog key={selected.id} person={selected} onClose={() => setSelected(null)} />}
    </>
  )
}
```

`frontend/src/features/settings/users/UserDialog.tsx`:

```tsx
import { zodResolver } from '@hookform/resolvers/zod'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { toast } from 'sonner'
import { z } from 'zod'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Separator } from '@/components/ui/separator'
import { Checkbox } from '@/components/form/Checkbox'
import { FormError } from '@/components/form/FormError'
import { TextField } from '@/components/form/TextField'
import { ListSkeleton } from '@/components/states'
import { StatusBadge } from '@/components/StatusBadge'
import { requiredText } from '@/features/auth/schemas'
import { PERMISSIONS, useCan } from '@/features/auth/permissions'
import { useTenantSession } from '@/features/auth/tenantSession'
import { useApi } from '@/lib/api/ApiContext'
import { applyFieldErrors, problemMessage } from '@/lib/api/problems'
import type { RoleView, UserView } from '@/lib/api/types'
import { fullName } from '@/lib/format'

/** Manage one person. Each section is shown only with its permission; the server re-checks every call. */
export function UserDialog({ person, onClose }: { person: UserView; onClose: () => void }) {
  const can = useCan()
  const session = useTenantSession()
  const queryClient = useQueryClient()
  const [current, setCurrent] = useState(person)
  const isSelf = session.state.status === 'authenticated' && session.state.profile.user.id === person.id

  async function changed(updated: UserView, message: string) {
    setCurrent(updated)
    toast.success(message)
    await queryClient.invalidateQueries({ queryKey: ['users'] })
    if (isSelf) await session.reloadProfile()
  }

  return (
    <Dialog open onOpenChange={(open) => { if (!open) onClose() }}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>{fullName(current)}</DialogTitle>
          <DialogDescription>
            {current.email} · <StatusBadge status={current.status} />
          </DialogDescription>
        </DialogHeader>
        {can(PERMISSIONS.userUpdate) && <NameSection person={current} onChanged={changed} />}
        {can(PERMISSIONS.userDisable) && !isSelf && current.status !== 'INVITED' && (
          <>
            <Separator />
            <StatusSection person={current} onChanged={changed} />
          </>
        )}
        {can(PERMISSIONS.roleAssign) && (
          <>
            <Separator />
            <RolesSection person={current} onChanged={changed} />
          </>
        )}
      </DialogContent>
    </Dialog>
  )
}

type Changed = (updated: UserView, message: string) => Promise<void>

const nameSchema = z.object({ firstName: requiredText(80), lastName: requiredText(80) })
type NameValues = z.infer<typeof nameSchema>

function NameSection({ person, onChanged }: { person: UserView; onChanged: Changed }) {
  const api = useApi()
  const [error, setError] = useState<string | null>(null)
  const form = useForm<NameValues>({
    resolver: zodResolver(nameSchema),
    defaultValues: { firstName: person.firstName, lastName: person.lastName },
  })
  const submit = form.handleSubmit(async (values) => {
    setError(null)
    try {
      await onChanged(await api.patch<UserView>(`/users/${person.id}`, values), 'Name updated.')
    } catch (e) {
      if (!applyFieldErrors(e, form.setError, ['firstName', 'lastName'] as const)) setError(problemMessage(e))
    }
  })
  return (
    <form noValidate onSubmit={submit} className="space-y-3">
      <h2 className="text-sm font-semibold">Name</h2>
      <div className="grid gap-3 sm:grid-cols-2">
        <TextField form={form} name="firstName" label="First name" />
        <TextField form={form} name="lastName" label="Last name" />
      </div>
      <FormError message={error} />
      <Button type="submit" size="sm" disabled={form.formState.isSubmitting}>
        Save name
      </Button>
    </form>
  )
}

function StatusSection({ person, onChanged }: { person: UserView; onChanged: Changed }) {
  const api = useApi()
  const [confirming, setConfirming] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const disabling = person.status === 'ACTIVE'

  async function apply() {
    setBusy(true)
    setError(null)
    try {
      const updated = await api.patch<UserView>(`/users/${person.id}`, { status: disabling ? 'DISABLED' : 'ACTIVE' })
      setConfirming(false)
      await onChanged(updated, disabling ? 'User disabled.' : 'User enabled.')
    } catch (e) {
      setError(problemMessage(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <section className="space-y-3">
      <h2 className="text-sm font-semibold">Access</h2>
      {!confirming ? (
        <Button variant={disabling ? 'destructive' : 'outline'} size="sm" onClick={() => (disabling ? setConfirming(true) : void apply())} disabled={busy}>
          {disabling ? 'Disable user' : 'Enable user'}
        </Button>
      ) : (
        <div className="space-y-2">
          <p className="text-sm">{fullName(person)} will be signed out immediately and can't sign in until enabled again.</p>
          <div className="flex gap-2">
            <Button variant="destructive" size="sm" onClick={() => void apply()} disabled={busy}>
              Confirm disable
            </Button>
            <Button variant="ghost" size="sm" onClick={() => setConfirming(false)}>
              Cancel
            </Button>
          </div>
        </div>
      )}
      <FormError message={error} />
    </section>
  )
}

function RolesSection({ person, onChanged }: { person: UserView; onChanged: Changed }) {
  const api = useApi()
  const can = useCan()
  const canReadRoles = can(PERMISSIONS.roleRead)
  const roles = useQuery({ queryKey: ['roles'], queryFn: () => api.get<RoleView[]>('/roles'), enabled: canReadRoles })
  const [chosen, setChosen] = useState<string[]>(() => person.roles.map((r) => r.id))
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function save() {
    setBusy(true)
    setError(null)
    try {
      await onChanged(await api.put<UserView>(`/users/${person.id}/roles`, { roleIds: chosen }), 'Roles updated.')
    } catch (e) {
      setError(problemMessage(e))
    } finally {
      setBusy(false)
    }
  }

  if (!canReadRoles) {
    return <p className="text-sm text-muted-foreground">You need permission to view roles before you can change them.</p>
  }
  return (
    <section className="space-y-3">
      <h2 className="text-sm font-semibold">Roles</h2>
      {roles.isPending ? (
        <ListSkeleton rows={3} />
      ) : roles.isError ? (
        <FormError message={problemMessage(roles.error)} />
      ) : (
        <fieldset className="space-y-2">
          <legend className="sr-only">Roles</legend>
          {roles.data.map((role) => (
            <label key={role.id} className="flex items-center gap-2 text-sm">
              <Checkbox
                checked={chosen.includes(role.id)}
                onChange={(e) =>
                  setChosen((ids) => (e.target.checked ? [...ids, role.id] : ids.filter((id) => id !== role.id)))
                }
              />
              {role.name}
            </label>
          ))}
        </fieldset>
      )}
      <FormError message={error} />
      <Button size="sm" onClick={() => void save()} disabled={busy || roles.isPending}>
        Save roles
      </Button>
    </section>
  )
}
```

In `frontend/src/features/shell/routes.tsx`, insert before `*` in `settingsChildren`:

```tsx
  {
    path: 'users',
    element: (
      <RequirePermission anyOf={[PERMISSIONS.userRead]}>
        <UsersPage />
      </RequirePermission>
    ),
  },
```

Also add the `UsersPage` import.

- [ ] **Step 5: Run the tests to verify they pass, then the full gate**

Run: `cd frontend && npm test -- UsersPage`
Expected: PASS (8 tests).

If Base UI's dialog doesn't expose `role="dialog"` in jsdom, scope the tests through `screen.getByRole('heading', { name: 'Grace Hopper' }).closest('[role="dialog"], [data-slot="dialog-content"]')`, and record the deviation. Do not drop the assertions.

Run: `make test-frontend`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add frontend
git commit -m "feat(frontend): user administration with filters, paging, rename, access and roles"
```

---

### Task 7: Settings — Invitations (list, invite, revoke)

**Files:**
- Create: `frontend/src/components/ConfirmDialog.tsx`
- Create: `frontend/src/features/settings/users/InvitationsPanel.tsx`
- Create: `frontend/src/features/settings/users/InviteDialog.tsx`
- Modify: `frontend/src/features/settings/users/UsersPage.tsx` (replace the `{/* invitations: Task 7 */}` slot with `<InvitationsPanel />`)
- Modify: `frontend/src/features/settings/users/UsersPage.test.tsx`: no change needed, since its `setup()` already stubs `GET /invitations`.
- Test: `frontend/src/features/settings/users/InvitationsPanel.test.tsx`

**Interfaces:**
- Consumes (Tasks 1, 2, 6): `useApi`, `useCan`, `PERMISSIONS`, `InvitationView`, `RoleView`, `StatusBadge`, `formatDateTime`, `TextField`, `FormError`, `NativeSelect`, `Field`, `describedBy`, `applyFieldErrors`, `problemMessage`, `emailField`, the states, and the shadcn `Dialog*`/`Table*`.
- Produces: `ConfirmDialog({ open, title, description, confirmLabel, onConfirm, onCancel, busy, error })`.

- [ ] **Step 1: Write the failing test**

`frontend/src/features/settings/users/InvitationsPanel.test.tsx`:

```tsx
import { screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import { renderApp } from '@/test/renderApp'

const PENDING = {
  id: 'i-1',
  email: 'linus@acme.test',
  roleId: 'r-support',
  roleName: 'Support',
  status: 'PENDING',
  invitedBy: 'u-ada',
  expiresAt: '2026-10-13T09:00:00Z',
  createdAt: '2026-10-06T09:00:00Z',
}
const ROLES = [
  { id: 'r-support', name: 'Support', description: null, system: false, permissions: [] },
  { id: 'r-admin', name: 'TENANT_ADMIN', description: null, system: true, permissions: [] },
]

function setup(profile = testProfile(), invitations: unknown[] = [PENDING]) {
  const server = fakeServer()
  signedIn(server, profile)
    .on('GET /users', { body: { items: [], page: 0, size: 20, total: 0 } })
    .on('GET /invitations', { body: invitations })
    .on('GET /roles', { body: ROLES })
  return { server, ...renderApp({ server, path: '/app/settings/users' }) }
}

describe('InvitationsPanel', () => {
  it('invites someone with a role', async () => {
    const { server, user } = setup()
    server.on('POST /invitations', (req) => ({
      status: 201,
      body: { ...PENDING, id: 'i-2', email: (req.body as { email: string }).email },
    }))
    await user.click(await screen.findByRole('button', { name: 'Invite people' }))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByLabelText('Email'), 'grace@acme.test')
    await user.selectOptions(within(dialog).getByLabelText('Role'), 'r-support')
    await user.click(within(dialog).getByRole('button', { name: 'Send invitation' }))
    expect(await screen.findByText('Invitation sent to grace@acme.test.')).toBeInTheDocument()
    expect(server.callsTo('POST /invitations')[0].body).toEqual({ email: 'grace@acme.test', roleId: 'r-support' })
  })

  it('shows a conflicting email under the field', async () => {
    const { server, user } = setup()
    server.on('POST /invitations', {
      status: 409,
      body: { detail: 'Conflict', errors: [{ field: 'email', message: 'An invitation is already pending for this email.' }] },
    })
    await user.click(await screen.findByRole('button', { name: 'Invite people' }))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByLabelText('Email'), 'linus@acme.test')
    await user.click(within(dialog).getByRole('button', { name: 'Send invitation' }))
    expect(await within(dialog).findByText('An invitation is already pending for this email.')).toBeInTheDocument()
    expect(within(dialog).getByLabelText('Email')).toHaveValue('linus@acme.test')
  })

  it('shows the plan limit as a form error', async () => {
    const { server, user } = setup()
    server.on('POST /invitations', { status: 409, body: { detail: 'Your plan allows 3 users. Upgrade to add more.' } })
    await user.click(await screen.findByRole('button', { name: 'Invite people' }))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByLabelText('Email'), 'new@acme.test')
    await user.click(within(dialog).getByRole('button', { name: 'Send invitation' }))
    expect(await within(dialog).findByText('Your plan allows 3 users. Upgrade to add more.')).toBeInTheDocument()
  })

  it('revokes a pending invitation after confirming', async () => {
    const { server, user } = setup()
    server.on('DELETE /invitations/:id', { status: 204 })
    await user.click(await screen.findByRole('button', { name: 'Revoke invitation for linus@acme.test' }))
    await user.click(await screen.findByRole('button', { name: 'Revoke' }))
    expect(await screen.findByText('Invitation revoked.')).toBeInTheDocument()
    expect(server.callsTo('DELETE /invitations/:id')[0].params).toEqual({ id: 'i-1' })
  })

  it('hides invite and revoke without the invite permission', async () => {
    setup(testProfile({ permissions: ['identity.user.read'] }))
    expect(await screen.findByText('linus@acme.test')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Invite people' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Revoke invitation/ })).not.toBeInTheDocument()
  })

  it('shows an empty state', async () => {
    setup(testProfile(), [])
    expect(await screen.findByText('No invitations yet.')).toBeInTheDocument()
  })
})
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd frontend && npm test -- InvitationsPanel`
Expected: FAIL (no "Invite people" button).

- [ ] **Step 3: Implement the confirm dialog, panel and invite dialog**

`frontend/src/components/ConfirmDialog.tsx`:

```tsx
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { FormError } from '@/components/form/FormError'

export function ConfirmDialog({
  open,
  title,
  description,
  confirmLabel,
  onConfirm,
  onCancel,
  busy = false,
  error = null,
}: {
  open: boolean
  title: string
  description: string
  confirmLabel: string
  onConfirm: () => void
  onCancel: () => void
  busy?: boolean
  error?: string | null
}) {
  return (
    <Dialog open={open} onOpenChange={(next) => { if (!next) onCancel() }}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
          <DialogDescription>{description}</DialogDescription>
        </DialogHeader>
        <FormError message={error} />
        <DialogFooter>
          <Button variant="ghost" onClick={onCancel} disabled={busy}>
            Cancel
          </Button>
          <Button variant="destructive" onClick={onConfirm} disabled={busy}>
            {confirmLabel}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
```

`frontend/src/features/settings/users/InviteDialog.tsx`:

```tsx
import { zodResolver } from '@hookform/resolvers/zod'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { useForm } from 'react-hook-form'
import { toast } from 'sonner'
import { z } from 'zod'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { describedBy, Field } from '@/components/form/Field'
import { FormError } from '@/components/form/FormError'
import { NativeSelect } from '@/components/form/NativeSelect'
import { TextField } from '@/components/form/TextField'
import { ListSkeleton } from '@/components/states'
import { emailField, MESSAGES } from '@/features/auth/schemas'
import { PERMISSIONS, useCan } from '@/features/auth/permissions'
import { useApi } from '@/lib/api/ApiContext'
import { applyFieldErrors, problemMessage } from '@/lib/api/problems'
import type { InvitationView, RoleView } from '@/lib/api/types'

const schema = z.object({ email: emailField, roleId: z.string().min(1, MESSAGES.required) })
type Values = z.infer<typeof schema>

export function InviteDialog({ onClose }: { onClose: () => void }) {
  const api = useApi()
  const canReadRoles = useCan()(PERMISSIONS.roleRead)
  const queryClient = useQueryClient()
  const roles = useQuery({ queryKey: ['roles'], queryFn: () => api.get<RoleView[]>('/roles'), enabled: canReadRoles })
  const [formError, setFormError] = useState<string | null>(null)
  const form = useForm<Values>({ resolver: zodResolver(schema), defaultValues: { email: '', roleId: '' } })
  const roleError = form.formState.errors.roleId?.message
  // Custom roles first: they are what most invitations should use.
  const options = roles.data ? [...roles.data].sort((a, b) => Number(a.system) - Number(b.system)) : []
  const firstOption = options[0]?.id
  useEffect(() => {
    if (firstOption && !form.getValues('roleId')) form.setValue('roleId', firstOption)
  }, [firstOption, form])

  const submit = form.handleSubmit(async (values) => {
    setFormError(null)
    try {
      const invitation = await api.post<InvitationView>('/invitations', values)
      toast.success(`Invitation sent to ${invitation.email}.`)
      await queryClient.invalidateQueries({ queryKey: ['invitations'] })
      onClose()
    } catch (error) {
      if (!applyFieldErrors(error, form.setError, ['email', 'roleId'] as const)) setFormError(problemMessage(error))
    }
  })

  return (
    <Dialog open onOpenChange={(open) => { if (!open) onClose() }}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Invite people</DialogTitle>
          <DialogDescription>They'll get an email with a link that's valid for 7 days.</DialogDescription>
        </DialogHeader>
        {!canReadRoles ? (
          <p className="text-sm">You need permission to view roles before you can invite people.</p>
        ) : roles.isPending ? (
          <ListSkeleton rows={2} />
        ) : (
          <form noValidate onSubmit={submit} className="space-y-4">
            <TextField form={form} name="email" label="Email" type="email" autoComplete="off" />
            <Field id="field-roleId" label="Role" error={roleError}>
              <NativeSelect
                id="field-roleId"
                aria-invalid={roleError ? true : undefined}
                aria-describedby={describedBy('field-roleId', roleError)}
                {...form.register('roleId')}
              >
                {options.map((role) => (
                  <option key={role.id} value={role.id}>
                    {role.name}
                  </option>
                ))}
              </NativeSelect>
            </Field>
            <FormError message={formError ?? (roles.isError ? problemMessage(roles.error) : null)} />
            <DialogFooter>
              <Button type="button" variant="ghost" onClick={onClose}>
                Cancel
              </Button>
              <Button type="submit" disabled={form.formState.isSubmitting}>
                Send invitation
              </Button>
            </DialogFooter>
          </form>
        )}
      </DialogContent>
    </Dialog>
  )
}
```

`frontend/src/features/settings/users/InvitationsPanel.tsx`:

```tsx
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { ConfirmDialog } from '@/components/ConfirmDialog'
import { StatusBadge } from '@/components/StatusBadge'
import { EmptyState, ErrorState, ListSkeleton } from '@/components/states'
import { PERMISSIONS, useCan } from '@/features/auth/permissions'
import { useApi } from '@/lib/api/ApiContext'
import { problemMessage } from '@/lib/api/problems'
import type { InvitationView } from '@/lib/api/types'
import { formatDateTime } from '@/lib/format'
import { InviteDialog } from './InviteDialog'

export function InvitationsPanel() {
  const api = useApi()
  const queryClient = useQueryClient()
  const canInvite = useCan()(PERMISSIONS.userInvite)
  const [inviting, setInviting] = useState(false)
  const [revoking, setRevoking] = useState<InvitationView | null>(null)
  const [busy, setBusy] = useState(false)
  const [revokeError, setRevokeError] = useState<string | null>(null)
  const invitations = useQuery({ queryKey: ['invitations'], queryFn: () => api.get<InvitationView[]>('/invitations') })

  async function revoke() {
    if (!revoking) return
    setBusy(true)
    setRevokeError(null)
    try {
      await api.del(`/invitations/${revoking.id}`)
      toast.success('Invitation revoked.')
      setRevoking(null)
      await queryClient.invalidateQueries({ queryKey: ['invitations'] })
    } catch (error) {
      setRevokeError(problemMessage(error))
    } finally {
      setBusy(false)
    }
  }

  return (
    <section aria-labelledby="invitations-heading" className="mt-10">
      <div className="mb-3 flex items-center justify-between">
        <h2 id="invitations-heading" className="text-lg font-semibold">
          Invitations
        </h2>
        {canInvite && <Button onClick={() => setInviting(true)}>Invite people</Button>}
      </div>
      {invitations.isPending ? (
        <ListSkeleton rows={3} />
      ) : invitations.isError ? (
        <ErrorState error={invitations.error} onRetry={() => void invitations.refetch()} />
      ) : invitations.data.length === 0 ? (
        <EmptyState title="No invitations yet." description="Invite teammates to give them access to this workspace." />
      ) : (
        <div className="overflow-x-auto rounded-lg border">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Email</TableHead>
                <TableHead>Role</TableHead>
                <TableHead>Status</TableHead>
                <TableHead>Expires</TableHead>
                {canInvite && <TableHead><span className="sr-only">Actions</span></TableHead>}
              </TableRow>
            </TableHeader>
            <TableBody>
              {invitations.data.map((invitation) => (
                <TableRow key={invitation.id}>
                  <TableCell>{invitation.email}</TableCell>
                  <TableCell>{invitation.roleName}</TableCell>
                  <TableCell>
                    <StatusBadge status={invitation.status} />
                  </TableCell>
                  <TableCell>{formatDateTime(invitation.expiresAt)}</TableCell>
                  {canInvite && (
                    <TableCell className="text-right">
                      {invitation.status === 'PENDING' && (
                        <Button
                          variant="ghost"
                          size="sm"
                          aria-label={`Revoke invitation for ${invitation.email}`}
                          onClick={() => { setRevokeError(null); setRevoking(invitation) }}
                        >
                          Revoke
                        </Button>
                      )}
                    </TableCell>
                  )}
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      )}
      {inviting && <InviteDialog onClose={() => setInviting(false)} />}
      <ConfirmDialog
        open={revoking !== null}
        title="Revoke invitation?"
        description={revoking ? `${revoking.email} won't be able to use their invitation link.` : ''}
        confirmLabel="Revoke"
        onConfirm={() => void revoke()}
        onCancel={() => setRevoking(null)}
        busy={busy}
        error={revokeError}
      />
    </section>
  )
}
```

In `UsersPage.tsx`, import `InvitationsPanel` and replace `{/* invitations: Task 7 */}` with `<InvitationsPanel />`.

The revoke button's visible text is "Revoke" and its accessible name is "Revoke invitation for …". The confirm button's name is "Revoke", so the test's `findByRole('button', { name: 'Revoke' })` resolves to the dialog button (exact name match).

- [ ] **Step 4: Run the tests to verify they pass, then the full gate**

Run: `cd frontend && npm test -- src/features/settings/users`
Expected: PASS (UsersPage 8 + InvitationsPanel 6).

Run: `make test-frontend`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend
git commit -m "feat(frontend): invite people, list and revoke invitations"
```

---

### Task 8: Settings — Roles (list, create, permission matrix, rename, delete)

**Files:**
- Create: `frontend/src/features/settings/roles/RolesPage.tsx`
- Create: `frontend/src/features/settings/roles/CreateRoleDialog.tsx`
- Create: `frontend/src/features/settings/roles/RoleDetailPage.tsx`
- Create: `frontend/src/features/settings/roles/PermissionMatrix.tsx`
- Modify: `frontend/src/features/shell/routes.tsx` (add `roles` and `roles/:roleId` before `*`)
- Test: `frontend/src/features/settings/roles/RolesPage.test.tsx`, `frontend/src/features/settings/roles/RoleDetailPage.test.tsx`

**Interfaces:**
- Consumes (Tasks 1, 2, 4, 6, 7): `useApi`, `useCan`, `PERMISSIONS`, `RequirePermission`, `useTenantSession`, `RoleView`, `PermissionView`, `MODULE_PHASES`, `ConfirmDialog`, `TextField`, `FormError`, `Checkbox`, `requiredText`, `applyFieldErrors`, `problemMessage`, the states, and the shadcn `Table*`/`Dialog*`/`Badge`.
- Produces: routes `/app/settings/roles` and `/app/settings/roles/:roleId`.

- [ ] **Step 1: Write the failing tests**

`frontend/src/features/settings/roles/RolesPage.test.tsx`:

```tsx
import { screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import { renderApp } from '@/test/renderApp'

const ROLES = [
  { id: 'r-owner', name: 'TENANT_OWNER', description: 'Full control', system: true, permissions: ['a', 'b'] },
  { id: 'r-support', name: 'Support', description: 'Front line', system: false, permissions: ['identity.user.read'] },
]

describe('RolesPage', () => {
  it('lists roles and creates one, then opens it', async () => {
    const server = fakeServer()
    signedIn(server)
      .on('GET /roles', { body: ROLES })
      .on('POST /roles', (req) => ({
        status: 201,
        body: { id: 'r-new', system: false, permissions: [], ...(req.body as object) },
      }))
      .on('GET /roles/:id', { body: { id: 'r-new', name: 'Auditor', description: null, system: false, permissions: [] } })
      .on('GET /permissions', { body: [] })
    const { user, router } = renderApp({ server, path: '/app/settings/roles' })
    const row = (await screen.findByRole('link', { name: 'Support' })).closest('tr') as HTMLElement
    expect(within(row).getByText('Custom')).toBeInTheDocument()
    expect(within(row).getByText('1')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'New role' }))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByLabelText('Name'), 'Auditor')
    await user.click(within(dialog).getByRole('button', { name: 'Create role' }))
    expect(await screen.findByText('Role created. Now choose its permissions.')).toBeInTheDocument()
    expect(server.callsTo('POST /roles')[0].body).toEqual({ name: 'Auditor', description: '', permissions: [] })
    expect(router.state.location.pathname).toBe('/app/settings/roles/r-new')
  })

  it('shows a duplicate-name error under the field', async () => {
    const server = fakeServer()
    signedIn(server)
      .on('GET /roles', { body: ROLES })
      .on('POST /roles', {
        status: 409,
        body: { detail: 'Conflict', errors: [{ field: 'name', message: 'A role with this name already exists.' }] },
      })
    const { user } = renderApp({ server, path: '/app/settings/roles' })
    await user.click(await screen.findByRole('button', { name: 'New role' }))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByLabelText('Name'), 'Support')
    await user.click(within(dialog).getByRole('button', { name: 'Create role' }))
    expect(await within(dialog).findByText('A role with this name already exists.')).toBeInTheDocument()
  })

  it('hides New role without the manage permission', async () => {
    const server = fakeServer()
    signedIn(server, testProfile({ permissions: ['authorization.role.read'] })).on('GET /roles', { body: ROLES })
    renderApp({ server, path: '/app/settings/roles' })
    await screen.findByRole('link', { name: 'Support' })
    expect(screen.queryByRole('button', { name: 'New role' })).not.toBeInTheDocument()
  })
})
```

`frontend/src/features/settings/roles/RoleDetailPage.test.tsx`:

```tsx
import { screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { fakeServer } from '@/test/fakeServer'
import { signedIn } from '@/test/fixtures'
import { renderApp } from '@/test/renderApp'

const CATALOG = [
  { code: 'identity.user.read', module: null, description: 'View users', moduleEnabled: true },
  { code: 'audit.event.read', module: null, description: 'View the audit log', moduleEnabled: true },
  { code: 'crm.customer.read', module: 'CRM', description: 'View customers', moduleEnabled: false },
]
const SUPPORT = { id: 'r-support', name: 'Support', description: 'Front line', system: false, permissions: ['identity.user.read'] }
const OWNER = { id: 'r-owner', name: 'TENANT_OWNER', description: null, system: true, permissions: ['identity.user.read'] }

function setup(role = SUPPORT) {
  const server = fakeServer()
  signedIn(server).on('GET /roles/:id', { body: role }).on('GET /permissions', { body: CATALOG })
  return { server, ...renderApp({ server, path: `/app/settings/roles/${role.id}` }) }
}

describe('RoleDetailPage', () => {
  it('groups permissions by module and saves the selection', async () => {
    const { server, user } = setup()
    server.on('PUT /roles/:id/permissions', (req) => ({ body: { ...SUPPORT, ...(req.body as object) } }))
    expect(await screen.findByRole('heading', { name: 'Support' })).toBeInTheDocument()
    expect(screen.getByRole('group', { name: 'Workspace administration' })).toBeInTheDocument()
    expect(screen.getByRole('group', { name: 'CRM module (not enabled)' })).toBeInTheDocument()
    expect(screen.getByRole('checkbox', { name: /View users/ })).toBeChecked()
    await user.click(screen.getByRole('checkbox', { name: /View the audit log/ }))
    await user.click(screen.getByRole('button', { name: 'Save permissions' }))
    expect(await screen.findByText('Permissions saved.')).toBeInTheDocument()
    expect(server.callsTo('PUT /roles/:id/permissions')[0].body).toEqual({
      permissions: ['audit.event.read', 'identity.user.read'],
    })
  })

  it("shows the server's hierarchy refusal", async () => {
    const { server, user } = setup()
    server.on('PUT /roles/:id/permissions', {
      status: 403,
      body: { detail: "You can't change a role with permissions you don't have." },
    })
    await user.click(await screen.findByRole('button', { name: 'Save permissions' }))
    expect(await screen.findByText("You can't change a role with permissions you don't have.")).toBeInTheDocument()
  })

  it('renames a role', async () => {
    const { server, user } = setup()
    server.on('PATCH /roles/:id', (req) => ({ body: { ...SUPPORT, ...(req.body as object) } }))
    const name = await screen.findByLabelText('Name')
    await user.clear(name)
    await user.type(name, 'Support L1')
    await user.click(screen.getByRole('button', { name: 'Save details' }))
    expect(await screen.findByText('Role updated.')).toBeInTheDocument()
    expect(server.callsTo('PATCH /roles/:id')[0].body).toEqual({ name: 'Support L1', description: 'Front line' })
  })

  it('deletes a role, or explains why it cannot', async () => {
    const { server, user, router } = setup()
    server.on('DELETE /roles/:id', { status: 409, body: { detail: 'This role is assigned to 2 users. Unassign it first.' } })
    await user.click(await screen.findByRole('button', { name: 'Delete role' }))
    await user.click(await screen.findByRole('button', { name: 'Delete' }))
    expect(await screen.findByText('This role is assigned to 2 users. Unassign it first.')).toBeInTheDocument()
    server.on('DELETE /roles/:id', { status: 204 }).on('GET /roles', { body: [] })
    await user.click(screen.getByRole('button', { name: 'Delete' }))
    expect(await screen.findByText('Role deleted.')).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/app/settings/roles')
  })

  it('keeps system roles read-only', async () => {
    setup(OWNER)
    expect(await screen.findByText("System roles can't be changed.")).toBeInTheDocument()
    expect(screen.getByRole('checkbox', { name: /View users/ })).toBeDisabled()
    expect(screen.queryByRole('button', { name: 'Save permissions' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Delete role' })).not.toBeInTheDocument()
  })
})
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd frontend && npm test -- src/features/settings/roles`
Expected: FAIL (no roles routes).

- [ ] **Step 3: Implement list, create dialog, detail page and matrix**

`frontend/src/features/settings/roles/CreateRoleDialog.tsx`:

```tsx
import { zodResolver } from '@hookform/resolvers/zod'
import { useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { useNavigate } from 'react-router'
import { toast } from 'sonner'
import { z } from 'zod'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { FormError } from '@/components/form/FormError'
import { TextField } from '@/components/form/TextField'
import { requiredText } from '@/features/auth/schemas'
import { useApi } from '@/lib/api/ApiContext'
import { applyFieldErrors, problemMessage } from '@/lib/api/problems'
import type { RoleView } from '@/lib/api/types'

const schema = z.object({ name: requiredText(60), description: z.string().trim().max(300, 'Use at most 300 characters.') })
type Values = z.infer<typeof schema>

export function CreateRoleDialog({ onClose }: { onClose: () => void }) {
  const api = useApi()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [formError, setFormError] = useState<string | null>(null)
  const form = useForm<Values>({ resolver: zodResolver(schema), defaultValues: { name: '', description: '' } })

  const submit = form.handleSubmit(async (values) => {
    setFormError(null)
    try {
      const role = await api.post<RoleView>('/roles', { ...values, permissions: [] })
      await queryClient.invalidateQueries({ queryKey: ['roles'] })
      toast.success('Role created. Now choose its permissions.')
      onClose()
      navigate(`/app/settings/roles/${role.id}`)
    } catch (error) {
      if (!applyFieldErrors(error, form.setError, ['name', 'description'] as const)) setFormError(problemMessage(error))
    }
  })

  return (
    <Dialog open onOpenChange={(open) => { if (!open) onClose() }}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>New role</DialogTitle>
          <DialogDescription>Name it after a job, e.g. "Support agent". You'll pick permissions next.</DialogDescription>
        </DialogHeader>
        <form noValidate onSubmit={submit} className="space-y-4">
          <TextField form={form} name="name" label="Name" />
          <TextField form={form} name="description" label="Description" />
          <FormError message={formError} />
          <DialogFooter>
            <Button type="button" variant="ghost" onClick={onClose}>
              Cancel
            </Button>
            <Button type="submit" disabled={form.formState.isSubmitting}>
              Create role
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
```

`frontend/src/features/settings/roles/RolesPage.tsx`:

```tsx
import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { EmptyState, ErrorState, ListSkeleton, PageHeader } from '@/components/states'
import { PERMISSIONS, useCan } from '@/features/auth/permissions'
import { useApi } from '@/lib/api/ApiContext'
import type { RoleView } from '@/lib/api/types'
import { CreateRoleDialog } from './CreateRoleDialog'

export function RolesPage() {
  const api = useApi()
  const canManage = useCan()(PERMISSIONS.roleManage)
  const [creating, setCreating] = useState(false)
  const roles = useQuery({ queryKey: ['roles'], queryFn: () => api.get<RoleView[]>('/roles') })

  return (
    <>
      <PageHeader
        title="Roles"
        description="Bundles of permissions you assign to people."
        actions={canManage ? <Button onClick={() => setCreating(true)}>New role</Button> : undefined}
      />
      {roles.isPending ? (
        <ListSkeleton />
      ) : roles.isError ? (
        <ErrorState error={roles.error} onRetry={() => void roles.refetch()} />
      ) : roles.data.length === 0 ? (
        <EmptyState title="No roles yet." description="Create a role to describe what a job can do." />
      ) : (
        <div className="overflow-x-auto rounded-lg border">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Role</TableHead>
                <TableHead>Description</TableHead>
                <TableHead>Type</TableHead>
                <TableHead>Permissions</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {roles.data.map((role) => (
                <TableRow key={role.id}>
                  <TableCell>
                    <Link to={`/app/settings/roles/${role.id}`} className="font-medium underline-offset-4 hover:underline">
                      {role.name}
                    </Link>
                  </TableCell>
                  <TableCell className="text-muted-foreground">{role.description ?? '—'}</TableCell>
                  <TableCell>
                    <Badge variant={role.system ? 'secondary' : 'outline'}>{role.system ? 'System' : 'Custom'}</Badge>
                  </TableCell>
                  <TableCell>{role.permissions.length}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      )}
      {creating && <CreateRoleDialog onClose={() => setCreating(false)} />}
    </>
  )
}
```

`frontend/src/features/settings/roles/PermissionMatrix.tsx`:

```tsx
import { Checkbox } from '@/components/form/Checkbox'
import { MODULE_PHASES } from '@/features/shell/nav'
import type { PermissionView } from '@/lib/api/types'

/** Permissions grouped by module; foundation permissions (module null) first. */
export function PermissionMatrix({
  catalog,
  selected,
  disabled,
  onToggle,
}: {
  catalog: PermissionView[]
  selected: ReadonlySet<string>
  disabled: boolean
  onToggle: (code: string, on: boolean) => void
}) {
  const groups = new Map<string, PermissionView[]>()
  for (const permission of catalog) {
    const key = permission.module ?? ''
    groups.set(key, [...(groups.get(key) ?? []), permission])
  }
  const keys = [...groups.keys()].sort((a, b) => (a === '' ? -1 : b === '' ? 1 : a.localeCompare(b)))

  return (
    <div className="space-y-4">
      {keys.map((key) => {
        const items = groups.get(key) ?? []
        const label =
          key === ''
            ? 'Workspace administration'
            : `${MODULE_PHASES[key]?.label ?? key} module${items[0]?.moduleEnabled ? '' : ' (not enabled)'}`
        return (
          <fieldset key={key || 'foundation'} className="rounded-lg border p-4">
            <legend className="px-1 text-sm font-semibold">{label}</legend>
            <div className="grid gap-2 sm:grid-cols-2">
              {items.map((permission) => (
                <label key={permission.code} className="flex items-start gap-2 text-sm">
                  <Checkbox
                    className="mt-0.5"
                    checked={selected.has(permission.code)}
                    disabled={disabled}
                    onChange={(e) => onToggle(permission.code, e.target.checked)}
                  />
                  <span>
                    {permission.description}
                    <span className="block text-xs text-muted-foreground">{permission.code}</span>
                  </span>
                </label>
              ))}
            </div>
          </fieldset>
        )
      })}
    </div>
  )
}
```

A `<fieldset>` with a `<legend>` has the ARIA role `group`, named by the legend, which is what the tests query.

`frontend/src/features/settings/roles/RoleDetailPage.tsx`:

```tsx
import { zodResolver } from '@hookform/resolvers/zod'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState, type ReactNode } from 'react'
import { useForm } from 'react-hook-form'
import { Link, useNavigate, useParams } from 'react-router'
import { toast } from 'sonner'
import { z } from 'zod'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { ConfirmDialog } from '@/components/ConfirmDialog'
import { FormError } from '@/components/form/FormError'
import { TextField } from '@/components/form/TextField'
import { ErrorState, ListSkeleton, PageHeader } from '@/components/states'
import { requiredText } from '@/features/auth/schemas'
import { PERMISSIONS, useCan } from '@/features/auth/permissions'
import { useTenantSession } from '@/features/auth/tenantSession'
import { useApi } from '@/lib/api/ApiContext'
import { applyFieldErrors, problemMessage } from '@/lib/api/problems'
import type { PermissionView, RoleView } from '@/lib/api/types'
import { PermissionMatrix } from './PermissionMatrix'

export function RoleDetailPage() {
  const { roleId = '' } = useParams()
  const api = useApi()
  const role = useQuery({ queryKey: ['role', roleId], queryFn: () => api.get<RoleView>(`/roles/${roleId}`) })
  const catalog = useQuery({ queryKey: ['permissions'], queryFn: () => api.get<PermissionView[]>('/permissions') })

  const back = (
    <Link to="/app/settings/roles" className="text-sm underline-offset-4 hover:underline">
      ← All roles
    </Link>
  )
  if (role.isPending || catalog.isPending) return <>{back}<ListSkeleton /></>
  if (role.isError) return <>{back}<ErrorState error={role.error} onRetry={() => void role.refetch()} /></>
  if (catalog.isError) return <>{back}<ErrorState error={catalog.error} onRetry={() => void catalog.refetch()} /></>
  return <RoleEditor key={role.data.id} role={role.data} catalog={catalog.data} back={back} />
}

const detailsSchema = z.object({ name: requiredText(60), description: z.string().trim().max(300, 'Use at most 300 characters.') })
type DetailsValues = z.infer<typeof detailsSchema>

function RoleEditor({ role, catalog, back }: { role: RoleView; catalog: PermissionView[]; back: ReactNode }) {
  const api = useApi()
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const session = useTenantSession()
  const canManage = useCan()(PERMISSIONS.roleManage) && !role.system
  const [selected, setSelected] = useState<Set<string>>(() => new Set(role.permissions))
  const [saving, setSaving] = useState(false)
  const [permissionsError, setPermissionsError] = useState<string | null>(null)
  const [detailsError, setDetailsError] = useState<string | null>(null)
  const [deleting, setDeleting] = useState(false)
  const [deleteBusy, setDeleteBusy] = useState(false)
  const [deleteError, setDeleteError] = useState<string | null>(null)
  const form = useForm<DetailsValues>({
    resolver: zodResolver(detailsSchema),
    defaultValues: { name: role.name, description: role.description ?? '' },
  })

  function stored(updated: RoleView) {
    queryClient.setQueryData(['role', updated.id], updated)
    void queryClient.invalidateQueries({ queryKey: ['roles'] })
  }

  const saveDetails = form.handleSubmit(async (values) => {
    setDetailsError(null)
    try {
      stored(await api.patch<RoleView>(`/roles/${role.id}`, values))
      toast.success('Role updated.')
    } catch (error) {
      if (!applyFieldErrors(error, form.setError, ['name', 'description'] as const)) setDetailsError(problemMessage(error))
    }
  })

  async function savePermissions() {
    setSaving(true)
    setPermissionsError(null)
    try {
      stored(await api.put<RoleView>(`/roles/${role.id}/permissions`, { permissions: [...selected].sort() }))
      toast.success('Permissions saved.')
      await session.reloadProfile() // the signed-in user may hold this role
    } catch (error) {
      setPermissionsError(problemMessage(error))
    } finally {
      setSaving(false)
    }
  }

  async function remove() {
    setDeleteBusy(true)
    setDeleteError(null)
    try {
      await api.del(`/roles/${role.id}`)
      await queryClient.invalidateQueries({ queryKey: ['roles'] })
      toast.success('Role deleted.')
      setDeleting(false)
      navigate('/app/settings/roles', { replace: true })
    } catch (error) {
      setDeleteError(problemMessage(error))
    } finally {
      setDeleteBusy(false)
    }
  }

  return (
    <div className="space-y-6">
      {back}
      <PageHeader
        title={role.name}
        description={role.description ?? undefined}
        actions={
          <>
            <Badge variant={role.system ? 'secondary' : 'outline'}>{role.system ? 'System' : 'Custom'}</Badge>
            {canManage && (
              <Button variant="destructive" size="sm" onClick={() => { setDeleteError(null); setDeleting(true) }}>
                Delete role
              </Button>
            )}
          </>
        }
      />
      {role.system && <p className="text-sm text-muted-foreground">System roles can't be changed.</p>}
      {canManage && (
        <form noValidate onSubmit={saveDetails} className="max-w-xl space-y-3">
          <TextField form={form} name="name" label="Name" />
          <TextField form={form} name="description" label="Description" />
          <FormError message={detailsError} />
          <Button type="submit" size="sm" disabled={form.formState.isSubmitting}>
            Save details
          </Button>
        </form>
      )}
      <section aria-labelledby="permissions-heading" className="space-y-3">
        <h2 id="permissions-heading" className="text-lg font-semibold">
          Permissions
        </h2>
        <PermissionMatrix
          catalog={catalog}
          selected={selected}
          disabled={!canManage || saving}
          onToggle={(code, on) =>
            setSelected((current) => {
              const next = new Set(current)
              if (on) next.add(code)
              else next.delete(code)
              return next
            })
          }
        />
        <FormError message={permissionsError} />
        {canManage && (
          <Button onClick={() => void savePermissions()} disabled={saving}>
            Save permissions
          </Button>
        )}
      </section>
      <ConfirmDialog
        open={deleting}
        title={`Delete ${role.name}?`}
        description="People must not hold this role. Deleting it can't be undone."
        confirmLabel="Delete"
        onConfirm={() => void remove()}
        onCancel={() => setDeleting(false)}
        busy={deleteBusy}
        error={deleteError}
      />
    </div>
  )
}
```

In `frontend/src/features/shell/routes.tsx`, insert these before `*` in `settingsChildren`:

```tsx
  {
    path: 'roles',
    element: (
      <RequirePermission anyOf={[PERMISSIONS.roleRead]}>
        <RolesPage />
      </RequirePermission>
    ),
  },
  {
    path: 'roles/:roleId',
    element: (
      <RequirePermission anyOf={[PERMISSIONS.roleRead]}>
        <RoleDetailPage />
      </RequirePermission>
    ),
  },
```

- [ ] **Step 4: Run the tests to verify they pass, then the full gate**

Run: `cd frontend && npm test -- src/features/settings/roles`
Expected: PASS (8 tests).

Run: `make test-frontend`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend
git commit -m "feat(frontend): roles with a module-grouped permission matrix"
```

---
### Task 9: Audit log

**Files:**
- Create: `frontend/src/features/audit/AuditPage.tsx`
- Modify: `frontend/src/features/shell/routes.tsx` (add `audit` to `appChildren`, before the `settings` entry)
- Test: `frontend/src/features/audit/AuditPage.test.tsx`

**Interfaces:**
- Consumes:
  - Task 1: `useApi`, `RequirePermission`, `PERMISSIONS`, `AuditEvent`, `Page`, `applyFieldErrors`/`problemMessage`, the states, `formatDateTime`.
  - Task 6: `toQuery`, `Pagination`.
  - The shadcn `Table*`, `Input`, `Label`, `Button`.
- Produces: the route `/app/audit`.

- [ ] **Step 1: Write the failing test**

`frontend/src/features/audit/AuditPage.test.tsx`:

```tsx
import { screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import { renderApp } from '@/test/renderApp'

const EVENT = {
  id: 'e-1',
  occurredAt: '2026-10-06T09:30:00Z',
  actorType: 'USER',
  actorId: '0192aaaa-bbbb-7ccc-8ddd-eeeeffff0000',
  action: 'RoleCreated',
  entityType: 'Role',
  entityId: 'r-1',
  ip: '10.0.0.1',
  userAgent: 'Firefox',
  requestId: 'req-9',
  correlationId: 'req-9',
  before: null,
  after: { name: 'Support' },
  metadata: null,
}

describe('AuditPage', () => {
  it('lists events and expands their details', async () => {
    const server = fakeServer()
    signedIn(server).on('GET /audit-events', { body: { items: [EVENT], page: 0, size: 25, total: 1 } })
    const { user } = renderApp({ server, path: '/app/audit' })
    expect(await screen.findByText('RoleCreated')).toBeInTheDocument()
    expect(screen.getByText(/0192aaaa/)).toBeInTheDocument()
    const toggle = screen.getByRole('button', { name: 'Show details for RoleCreated' })
    await user.click(toggle)
    expect(toggle).toHaveAttribute('aria-expanded', 'true')
    expect(screen.getByText(/"name": "Support"/)).toBeInTheDocument()
    expect(screen.getByText(/req-9/)).toBeInTheDocument()
  })

  it('filters by action and date range', async () => {
    const server = fakeServer()
    signedIn(server).on('GET /audit-events', { body: { items: [], page: 0, size: 25, total: 0 } })
    const { user } = renderApp({ server, path: '/app/audit' })
    expect(await screen.findByText('No events match these filters.')).toBeInTheDocument()
    await user.type(screen.getByLabelText('Action'), 'LoginFailed')
    await user.type(screen.getByLabelText('From'), '2026-10-01')
    await user.type(screen.getByLabelText('To'), '2026-10-06')
    await user.click(screen.getByRole('button', { name: 'Apply filters' }))
    const query = server.callsTo('GET /audit-events').at(-1)?.query
    expect(query?.get('action')).toBe('LoginFailed')
    expect(query?.get('from')).toBe(new Date('2026-10-01T00:00:00').toISOString())
    expect(query?.get('to')).toBe(new Date('2026-10-06T23:59:59.999').toISOString())
    expect(query?.get('size')).toBe('25')
  })

  it('shows a server field error', async () => {
    const server = fakeServer()
    signedIn(server).on('GET /audit-events', {
      status: 400,
      body: { detail: 'Bad Request', errors: [{ field: 'from', message: "'from' must not be after 'to'." }] },
    })
    renderApp({ server, path: '/app/audit?from=2026-10-09&to=2026-10-01' })
    expect(await screen.findByText("'from' must not be after 'to'.")).toBeInTheDocument()
  })

  it('needs the audit permission', async () => {
    const server = fakeServer()
    signedIn(server, testProfile({ permissions: [] }))
    renderApp({ server, path: '/app/audit' })
    expect(await screen.findByRole('heading', { name: "You don't have access to this page" })).toBeInTheDocument()
  })
})
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd frontend && npm test -- AuditPage`
Expected: FAIL (no `/app/audit` route, so NotFound renders).

- [ ] **Step 3: Implement the page and route**

`frontend/src/features/audit/AuditPage.tsx`:

```tsx
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { Fragment, useState, type FormEvent } from 'react'
import { useSearchParams } from 'react-router'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { Pagination } from '@/components/Pagination'
import { EmptyState, ErrorState, ListSkeleton, PageHeader } from '@/components/states'
import { useApi } from '@/lib/api/ApiContext'
import { ApiError } from '@/lib/api/client'
import type { AuditEvent, Page } from '@/lib/api/types'
import { formatDateTime } from '@/lib/format'
import { toQuery } from '@/lib/query'

const SIZE = 25
const FILTERS = ['action', 'entityType', 'from', 'to'] as const

/** Local calendar days → ISO instants (the API expects ISO-8601 instants). */
function dayStart(day: string): string | undefined {
  return day ? new Date(`${day}T00:00:00`).toISOString() : undefined
}
function dayEnd(day: string): string | undefined {
  return day ? new Date(`${day}T23:59:59.999`).toISOString() : undefined
}
function shortId(id: string | null): string {
  return id ? id.slice(0, 8) : '—'
}

export function AuditPage() {
  const api = useApi()
  const [params, setParams] = useSearchParams()
  const filters = Object.fromEntries(FILTERS.map((key) => [key, params.get(key) ?? ''])) as Record<
    (typeof FILTERS)[number],
    string
  >
  const page = Math.max(0, Number(params.get('page') ?? '0') || 0)
  const [open, setOpen] = useState<string | null>(null)

  const events = useQuery({
    queryKey: ['audit-events', filters, page],
    queryFn: () =>
      api.get<Page<AuditEvent>>(
        `/audit-events?${toQuery({
          action: filters.action.trim(),
          entityType: filters.entityType.trim(),
          from: dayStart(filters.from),
          to: dayEnd(filters.to),
          page,
          size: SIZE,
        })}`,
      ),
    placeholderData: keepPreviousData,
  })

  function onFilter(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const data = new FormData(event.currentTarget)
    const next = new URLSearchParams()
    for (const key of FILTERS) {
      const value = data.get(key)
      if (typeof value === 'string' && value.trim()) next.set(key, value.trim())
    }
    setParams(next, { replace: true })
  }

  const fieldErrors =
    events.error instanceof ApiError ? (events.error.problem.errors ?? []) : []

  return (
    <>
      <PageHeader title="Audit log" description="Every security-relevant action in this workspace. Read-only." />
      <form onSubmit={onFilter} className="mb-4 grid gap-3 sm:grid-cols-5 sm:items-end">
        <div className="space-y-1.5">
          <Label htmlFor="audit-action">Action</Label>
          <Input id="audit-action" name="action" defaultValue={filters.action} placeholder="e.g. LoginFailed" />
        </div>
        <div className="space-y-1.5">
          <Label htmlFor="audit-entity">Entity type</Label>
          <Input id="audit-entity" name="entityType" defaultValue={filters.entityType} placeholder="e.g. User" />
        </div>
        <div className="space-y-1.5">
          <Label htmlFor="audit-from">From</Label>
          <Input id="audit-from" name="from" type="date" defaultValue={filters.from} />
        </div>
        <div className="space-y-1.5">
          <Label htmlFor="audit-to">To</Label>
          <Input id="audit-to" name="to" type="date" defaultValue={filters.to} />
        </div>
        <Button type="submit" variant="outline">
          Apply filters
        </Button>
      </form>

      {events.isPending ? (
        <ListSkeleton />
      ) : events.isError ? (
        fieldErrors.length ? (
          <div role="alert" className="text-sm text-destructive">
            {fieldErrors.map((e) => (
              <p key={e.field}>{e.message}</p>
            ))}
          </div>
        ) : (
          <ErrorState error={events.error} onRetry={() => void events.refetch()} />
        )
      ) : events.data.items.length === 0 ? (
        <EmptyState title="No events match these filters." description="Try a wider date range or clear the filters." />
      ) : (
        <>
          <div className="overflow-x-auto rounded-lg border">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>When</TableHead>
                  <TableHead>Action</TableHead>
                  <TableHead>Actor</TableHead>
                  <TableHead>Entity</TableHead>
                  <TableHead><span className="sr-only">Details</span></TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {events.data.items.map((event) => {
                  const expanded = open === event.id
                  const details = Object.fromEntries(
                    Object.entries({
                      before: event.before,
                      after: event.after,
                      metadata: event.metadata,
                      requestId: event.requestId,
                      ip: event.ip,
                      userAgent: event.userAgent,
                    }).filter(([, value]) => value !== null && value !== undefined),
                  )
                  return (
                    <Fragment key={event.id}>
                      <TableRow>
                        <TableCell className="whitespace-nowrap">{formatDateTime(event.occurredAt)}</TableCell>
                        <TableCell className="font-medium">{event.action}</TableCell>
                        <TableCell>
                          {event.actorType} {shortId(event.actorId)}
                        </TableCell>
                        <TableCell>
                          {event.entityType ?? '—'} {event.entityId ?? ''}
                        </TableCell>
                        <TableCell className="text-right">
                          <Button
                            variant="ghost"
                            size="sm"
                            aria-expanded={expanded}
                            aria-controls={`audit-${event.id}`}
                            aria-label={`${expanded ? 'Hide' : 'Show'} details for ${event.action}`}
                            onClick={() => setOpen(expanded ? null : event.id)}
                          >
                            {expanded ? 'Hide' : 'Details'}
                          </Button>
                        </TableCell>
                      </TableRow>
                      {expanded && (
                        <TableRow id={`audit-${event.id}`}>
                          <TableCell colSpan={5}>
                            <pre className="max-h-80 overflow-auto rounded bg-muted p-3 text-xs">
                              {JSON.stringify(details, null, 2)}
                            </pre>
                          </TableCell>
                        </TableRow>
                      )}
                    </Fragment>
                  )
                })}
              </TableBody>
            </Table>
          </div>
          <Pagination
            page={events.data.page}
            size={events.data.size}
            total={events.data.total}
            onPage={(p) => {
              const next = new URLSearchParams(params)
              next.set('page', String(p))
              setParams(next, { replace: true })
            }}
          />
        </>
      )}
    </>
  )
}
```

After clicking, the toggle's accessible name changes to "Hide details for RoleCreated". The test keeps the element reference from before the click and asserts `aria-expanded` on that same element.

In `frontend/src/features/shell/routes.tsx`, insert this into `appChildren` before the `settings` entry:

```tsx
  {
    path: 'audit',
    element: (
      <RequirePermission anyOf={[PERMISSIONS.auditRead]}>
        <AuditPage />
      </RequirePermission>
    ),
  },
```

Add the `AuditPage` import.

- [ ] **Step 4: Run the tests to verify they pass, then the full gate**

Run: `cd frontend && npm test -- AuditPage`
Expected: PASS (4 tests).

Run: `make test-frontend`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend
git commit -m "feat(frontend): filterable audit log with event details"
```

---

### Task 10: Platform console — staff sign-in with TOTP, workspace list, suspend and reactivate

**Files:**
- Create: `frontend/src/features/platform/platformSession.ts`
- Create: `frontend/src/features/platform/PlatformRoot.tsx`
- Create: `frontend/src/features/platform/guards.tsx`
- Create: `frontend/src/features/platform/PlatformLoginPage.tsx`
- Create: `frontend/src/features/platform/PlatformLayout.tsx`
- Create: `frontend/src/features/platform/PlatformTenantsPage.tsx`
- Create: `frontend/src/features/platform/StatusChangeDialog.tsx`
- Create: `frontend/src/features/platform/routes.tsx`
- Modify: `frontend/src/app/router.tsx` (add the platform subtree before `*`)
- Modify: `frontend/src/test/fixtures.ts` (add `platformSignedIn`, `platformSignedOut`, `platformMe`)
- Test: `frontend/src/features/platform/PlatformLoginPage.test.tsx`, `frontend/src/features/platform/PlatformTenantsPage.test.tsx`

**Interfaces:**
- Consumes:
  - Tasks 1, 2, 6: `createSession`, `useApiHandles`, `ApiProvider`, `useApi`, `safeNext`, `PlatformMe`, `PlatformTenant`, `Page`, `TextField`, `FormError`, `Field`, `describedBy`, `NativeSelect`, `StatusBadge`, `Pagination`, `toQuery`, the states, `AuthCard`, `emailField`, `MESSAGES`, `problemMessage`, `applyFieldErrors`.
  - The shadcn `Textarea`, `Dialog*`, `Table*`.
- Produces: routes `/platform`, `/platform/login` and `/platform/tenants`; `usePlatformSession()`; `PLATFORM_SUSPEND = 'platform.tenant.suspend'`.

- [ ] **Step 1: Add the fixtures, then write the failing tests**

Append to `frontend/src/test/fixtures.ts`:

```ts
import type { PlatformMe } from '@/lib/api/types'

export function platformMe(overrides: Partial<PlatformMe> = {}): PlatformMe {
  return {
    id: 'p-ops',
    email: 'ops@nexusops.test',
    role: 'PLATFORM_ADMIN',
    permissions: ['platform.tenant.read', 'platform.tenant.suspend'],
    ...overrides,
  }
}

export function platformSignedIn(server: FakeServer, me: PlatformMe = platformMe()): FakeServer {
  return server
    .on('POST /platform/auth/refresh', { body: { accessToken: 'p-token', tokenType: 'Bearer', expiresIn: 900 } })
    .on('GET /platform/me', { body: me })
}

export function platformSignedOut(server: FakeServer): FakeServer {
  return server.on('POST /platform/auth/refresh', { status: 401, body: { detail: 'Your session has expired. Please sign in again.' } })
}
```

Merge the `import type { PlatformMe }` into the file's existing type import line from `@/lib/api/types`.

`frontend/src/features/platform/PlatformLoginPage.test.tsx`:

```tsx
import { screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { fakeServer } from '@/test/fakeServer'
import { platformMe, platformSignedOut } from '@/test/fixtures'
import { renderApp } from '@/test/renderApp'

function setup() {
  const server = fakeServer()
  platformSignedOut(server).on('GET /platform/tenants', { body: { items: [], page: 0, size: 20, total: 0 } })
  return { server, ...renderApp({ server, path: '/platform/login' }) }
}

async function fill(user: ReturnType<typeof setup>['user'], code: string) {
  await user.type(await screen.findByLabelText('Email'), 'ops@nexusops.test')
  await user.type(screen.getByLabelText('Password'), 'a platform passphrase')
  await user.type(screen.getByLabelText('Authenticator code'), code)
  await user.click(screen.getByRole('button', { name: 'Sign in' }))
}

describe('PlatformLoginPage', () => {
  it('requires a six-digit code', async () => {
    const { user, server } = setup()
    await fill(user, '12ab')
    expect(await screen.findByText('Enter the 6-digit code.')).toBeInTheDocument()
    expect(server.callsTo('POST /platform/auth/login')).toHaveLength(0)
  })

  it('signs in and opens the workspace list', async () => {
    const { user, server, router } = setup()
    server
      .on('POST /platform/auth/login', { body: { accessToken: 'p', tokenType: 'Bearer', expiresIn: 900 } })
      .on('GET /platform/me', { body: platformMe() })
    await fill(user, '123 456')
    expect(await screen.findByRole('heading', { name: 'Workspaces' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/platform/tenants')
    expect(server.callsTo('POST /platform/auth/login')[0].body).toEqual({
      email: 'ops@nexusops.test',
      password: 'a platform passphrase',
      code: '123456',
    })
    expect(server.callsTo('POST /auth/refresh')).toHaveLength(0) // the tenant session is never touched
  })

  it('shows the uniform failure', async () => {
    const { user, server } = setup()
    server.on('POST /platform/auth/login', { status: 401, body: { detail: 'Invalid email, password or code.' } })
    await fill(user, '000000')
    expect(await screen.findByText('Invalid email, password or code.')).toBeInTheDocument()
  })
})
```

`frontend/src/features/platform/PlatformTenantsPage.test.tsx`:

```tsx
import { screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { fakeServer } from '@/test/fakeServer'
import { platformMe, platformSignedIn } from '@/test/fixtures'
import { renderApp } from '@/test/renderApp'

const ACME = {
  id: 't-acme',
  slug: 'acme',
  name: 'Acme Inc',
  status: 'ACTIVE',
  planCode: 'FREE',
  createdAt: '2026-10-01T09:00:00Z',
  activeUsers: 3,
  ownerEmails: ['ada@acme.test'],
}

function setup(me = platformMe()) {
  const server = fakeServer()
  platformSignedIn(server, me).on('GET /platform/tenants', { body: { items: [ACME], page: 0, size: 20, total: 1 } })
  return { server, ...renderApp({ server, path: '/platform/tenants' }) }
}

describe('PlatformTenantsPage', () => {
  it('lists workspaces with counts and owners', async () => {
    setup()
    const row = (await screen.findByText('Acme Inc')).closest('tr') as HTMLElement
    expect(within(row).getByText('acme')).toBeInTheDocument()
    expect(within(row).getByText('3')).toBeInTheDocument()
    expect(within(row).getByText('ada@acme.test')).toBeInTheDocument()
    expect(within(row).getByText('Active')).toBeInTheDocument()
    expect(screen.getByText('ops@nexusops.test')).toBeInTheDocument()
  })

  it('searches and filters', async () => {
    const { server, user } = setup()
    await screen.findByText('Acme Inc')
    await user.type(screen.getByLabelText('Search workspaces'), 'acme')
    await user.selectOptions(screen.getByLabelText('Status'), 'SUSPENDED')
    await user.click(screen.getByRole('button', { name: 'Search' }))
    const query = server.callsTo('GET /platform/tenants').at(-1)?.query
    expect(query?.get('q')).toBe('acme')
    expect(query?.get('status')).toBe('SUSPENDED')
  })

  it('suspends with a required reason', async () => {
    const { server, user } = setup()
    server.on('POST /platform/tenants/:id/suspend', { body: { ...ACME, status: 'SUSPENDED' } })
    await user.click(await screen.findByRole('button', { name: 'Suspend Acme Inc' }))
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('button', { name: 'Suspend workspace' }))
    expect(await within(dialog).findByText('Enter a reason between 1 and 500 characters.')).toBeInTheDocument()
    await user.type(within(dialog).getByLabelText('Reason'), 'Abuse report 42')
    await user.click(within(dialog).getByRole('button', { name: 'Suspend workspace' }))
    expect(await screen.findByText('Acme Inc suspended.')).toBeInTheDocument()
    expect(server.callsTo('POST /platform/tenants/:id/suspend')[0]).toMatchObject({
      params: { id: 't-acme' },
      body: { reason: 'Abuse report 42' },
    })
  })

  it('offers reactivation for a suspended workspace and shows conflicts', async () => {
    const { server, user } = setup()
    server
      .on('GET /platform/tenants', { body: { items: [{ ...ACME, status: 'SUSPENDED' }], page: 0, size: 20, total: 1 } })
      .on('POST /platform/tenants/:id/reactivate', { status: 409, body: { detail: 'Only a suspended workspace can be reactivated.' } })
    await user.click(await screen.findByRole('button', { name: 'Reactivate Acme Inc' }))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByLabelText('Reason'), 'Resolved')
    await user.click(within(dialog).getByRole('button', { name: 'Reactivate workspace' }))
    expect(await within(dialog).findByText('Only a suspended workspace can be reactivated.')).toBeInTheDocument()
  })

  it('gives support staff no suspend actions', async () => {
    setup(platformMe({ role: 'PLATFORM_SUPPORT', permissions: ['platform.tenant.read'] }))
    await screen.findByText('Acme Inc')
    expect(screen.queryByRole('button', { name: /Suspend|Reactivate/ })).not.toBeInTheDocument()
  })

  it('sends signed-out staff to the platform sign-in', async () => {
    const server = fakeServer()
    server.on('POST /platform/auth/refresh', { status: 401, body: {} })
    const { router } = renderApp({ server, path: '/platform/tenants' })
    expect(await screen.findByRole('heading', { name: 'Staff sign-in' })).toBeInTheDocument()
    expect(router.state.location.search).toBe(`?next=${encodeURIComponent('/platform/tenants')}`)
  })
})
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd frontend && npm test -- src/features/platform`
Expected: FAIL (no `/platform` routes, so NotFound renders).

- [ ] **Step 3: Implement the platform session, root, guards and routes**

`frontend/src/features/platform/platformSession.ts`:

```ts
import type { PlatformMe } from '@/lib/api/types'
import { createSession } from '@/lib/session/createSession'

export interface PlatformLoginInput {
  email: string
  password: string
  code: string
}

export const PLATFORM_SUSPEND = 'platform.tenant.suspend'

export const { SessionProvider: PlatformSessionProvider, useSession: usePlatformSession } = createSession<
  PlatformMe,
  PlatformLoginInput
>({ login: '/platform/auth/login', logout: '/platform/auth/logout', profile: '/platform/me' }, 'Platform')
```

`frontend/src/features/platform/PlatformRoot.tsx`:

```tsx
import { Outlet } from 'react-router'
import { useApiHandles } from '@/app/handles'
import { ApiProvider } from '@/lib/api/ApiContext'
import { PlatformSessionProvider } from './platformSession'

/** /platform/* runs with the platform client and session only; the tenant session is never mounted here. */
export function PlatformRoot() {
  const { platform } = useApiHandles()
  return (
    <ApiProvider client={platform.client}>
      <PlatformSessionProvider api={platform}>
        <Outlet />
      </PlatformSessionProvider>
    </ApiProvider>
  )
}
```

`frontend/src/features/platform/guards.tsx`:

```tsx
import { Navigate, Outlet, useLocation, useSearchParams } from 'react-router'
import { FullPageLoading } from '@/components/states'
import { safeNext } from '@/features/auth/guards'
import { usePlatformSession } from './platformSession'

export function RequirePlatformSession() {
  const { state } = usePlatformSession()
  const location = useLocation()
  if (state.status === 'loading') return <FullPageLoading label="Loading the platform console…" />
  if (state.status === 'anonymous') {
    return <Navigate to={`/platform/login?next=${encodeURIComponent(location.pathname + location.search)}`} replace />
  }
  return <Outlet />
}

export function RedirectIfPlatformSignedIn() {
  const { state } = usePlatformSession()
  const [params] = useSearchParams()
  if (state.status === 'loading') return <FullPageLoading label="Loading…" />
  if (state.status === 'authenticated') {
    return <Navigate to={safeNext(params.get('next'), '/platform/tenants')} replace />
  }
  return <Outlet />
}
```

`frontend/src/features/platform/routes.tsx`:

```tsx
import { Navigate, type RouteObject } from 'react-router'
import { RedirectIfPlatformSignedIn, RequirePlatformSession } from './guards'
import { PlatformLayout } from './PlatformLayout'
import { PlatformLoginPage } from './PlatformLoginPage'
import { PlatformRoot } from './PlatformRoot'
import { PlatformTenantsPage } from './PlatformTenantsPage'

export const platformRoutes: RouteObject[] = [
  {
    path: '/platform',
    element: <PlatformRoot />,
    children: [
      { element: <RedirectIfPlatformSignedIn />, children: [{ path: 'login', element: <PlatformLoginPage /> }] },
      {
        element: <RequirePlatformSession />,
        children: [
          {
            element: <PlatformLayout />,
            children: [
              { index: true, element: <Navigate to="/platform/tenants" replace /> },
              { path: 'tenants', element: <PlatformTenantsPage /> },
            ],
          },
        ],
      },
    ],
  },
]
```

In `frontend/src/app/router.tsx`, import `platformRoutes` and spread `...platformRoutes` into the top-level array, before the `*` route.

- [ ] **Step 4: Implement the sign-in page, layout, tenants page and status dialog**

`frontend/src/features/platform/PlatformLoginPage.tsx`:

```tsx
import { zodResolver } from '@hookform/resolvers/zod'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { z } from 'zod'
import { Button } from '@/components/ui/button'
import { FormError } from '@/components/form/FormError'
import { TextField } from '@/components/form/TextField'
import { AuthCard } from '@/features/auth/AuthCard'
import { emailField, MESSAGES } from '@/features/auth/schemas'
import { problemMessage } from '@/lib/api/problems'
import { usePlatformSession } from './platformSession'

const schema = z.object({
  email: emailField,
  password: z.string().min(1, MESSAGES.required),
  // Six digits, spaces allowed (authenticator apps show "123 456"); stripped before sending.
  code: z.string().regex(/^(\s*\d){6}\s*$/, 'Enter the 6-digit code.'),
})
type Values = z.infer<typeof schema>

export function PlatformLoginPage() {
  const session = usePlatformSession()
  const [error, setError] = useState<string | null>(null)
  const form = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: { email: '', password: '', code: '' },
  })

  const submit = form.handleSubmit(async (values) => {
    setError(null)
    try {
      await session.login({ ...values, code: values.code.replace(/\s/g, '') })
    } catch (e) {
      setError(problemMessage(e))
    }
  })

  return (
    <AuthCard title="Staff sign-in" description="NexusOps platform console. Use your password and authenticator app.">
      <form noValidate onSubmit={submit} className="space-y-4">
        <TextField form={form} name="email" label="Email" type="email" autoComplete="username" />
        <TextField form={form} name="password" label="Password" type="password" autoComplete="current-password" />
        <TextField
          form={form}
          name="code"
          label="Authenticator code"
          inputMode="numeric"
          autoComplete="one-time-code"
          maxLength={7}
        />
        <FormError message={error} />
        <Button type="submit" className="w-full" disabled={form.formState.isSubmitting}>
          Sign in
        </Button>
      </form>
    </AuthCard>
  )
}
```

`frontend/src/features/platform/PlatformLayout.tsx`:

```tsx
import { Outlet } from 'react-router'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { usePlatformSession } from './platformSession'

export function PlatformLayout() {
  const session = usePlatformSession()
  if (session.state.status !== 'authenticated') return null
  const { profile } = session.state
  return (
    <div className="min-h-screen">
      <header className="flex flex-wrap items-center justify-between gap-3 border-b px-4 py-3 md:px-8">
        <p className="font-semibold">NexusOps Platform</p>
        <div className="flex items-center gap-3 text-sm">
          <span>{profile.email}</span>
          <Badge variant="secondary">{profile.role === 'PLATFORM_ADMIN' ? 'Admin' : 'Support'}</Badge>
          <Button variant="outline" size="sm" onClick={() => void session.logout()}>
            Sign out
          </Button>
        </div>
      </header>
      <main className="p-4 md:p-8">
        <Outlet />
      </main>
    </div>
  )
}
```

`frontend/src/features/platform/StatusChangeDialog.tsx`:

```tsx
import { zodResolver } from '@hookform/resolvers/zod'
import { useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { toast } from 'sonner'
import { z } from 'zod'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Textarea } from '@/components/ui/textarea'
import { describedBy, Field } from '@/components/form/Field'
import { FormError } from '@/components/form/FormError'
import { useApi } from '@/lib/api/ApiContext'
import { applyFieldErrors, problemMessage } from '@/lib/api/problems'
import type { PlatformTenant } from '@/lib/api/types'

const REASON = 'Enter a reason between 1 and 500 characters.'
const schema = z.object({ reason: z.string().trim().min(1, REASON).max(500, REASON) })
type Values = z.infer<typeof schema>

export function StatusChangeDialog({
  tenant,
  action,
  onClose,
}: {
  tenant: PlatformTenant
  action: 'suspend' | 'reactivate'
  onClose: () => void
}) {
  const api = useApi()
  const queryClient = useQueryClient()
  const [error, setError] = useState<string | null>(null)
  const form = useForm<Values>({ resolver: zodResolver(schema), defaultValues: { reason: '' } })
  const reasonError = form.formState.errors.reason?.message
  const suspending = action === 'suspend'

  const submit = form.handleSubmit(async (values) => {
    setError(null)
    try {
      await api.post<PlatformTenant>(`/platform/tenants/${tenant.id}/${action}`, values)
      toast.success(`${tenant.name} ${suspending ? 'suspended' : 'reactivated'}.`)
      await queryClient.invalidateQueries({ queryKey: ['platform-tenants'] })
      onClose()
    } catch (e) {
      if (!applyFieldErrors(e, form.setError, ['reason'] as const)) setError(problemMessage(e))
    }
  })

  return (
    <Dialog open onOpenChange={(open) => { if (!open) onClose() }}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>
            {suspending ? 'Suspend' : 'Reactivate'} {tenant.name}?
          </DialogTitle>
          <DialogDescription>
            {suspending
              ? 'Everyone in this workspace is blocked on their next request until it is reactivated. The reason is shown in its audit log.'
              : 'Members can sign in and work again. The reason is shown in its audit log.'}
          </DialogDescription>
        </DialogHeader>
        <form noValidate onSubmit={submit} className="space-y-4">
          <Field id="field-reason" label="Reason" error={reasonError}>
            <Textarea
              id="field-reason"
              rows={3}
              aria-invalid={reasonError ? true : undefined}
              aria-describedby={describedBy('field-reason', reasonError)}
              {...form.register('reason')}
            />
          </Field>
          <FormError message={error} />
          <DialogFooter>
            <Button type="button" variant="ghost" onClick={onClose}>
              Cancel
            </Button>
            <Button type="submit" variant={suspending ? 'destructive' : 'default'} disabled={form.formState.isSubmitting}>
              {suspending ? 'Suspend workspace' : 'Reactivate workspace'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
```

`frontend/src/features/platform/PlatformTenantsPage.tsx`:

```tsx
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { useSearchParams } from 'react-router'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { NativeSelect } from '@/components/form/NativeSelect'
import { Pagination } from '@/components/Pagination'
import { StatusBadge } from '@/components/StatusBadge'
import { EmptyState, ErrorState, ListSkeleton, PageHeader } from '@/components/states'
import { useApi } from '@/lib/api/ApiContext'
import type { Page, PlatformTenant } from '@/lib/api/types'
import { formatDateTime } from '@/lib/format'
import { toQuery } from '@/lib/query'
import { PLATFORM_SUSPEND, usePlatformSession } from './platformSession'
import { StatusChangeDialog } from './StatusChangeDialog'

const SIZE = 20

export function PlatformTenantsPage() {
  const api = useApi()
  const session = usePlatformSession()
  const [params, setParams] = useSearchParams()
  const [change, setChange] = useState<{ tenant: PlatformTenant; action: 'suspend' | 'reactivate' } | null>(null)
  const canSuspend = session.state.status === 'authenticated' && session.state.profile.permissions.includes(PLATFORM_SUSPEND)
  const q = params.get('q') ?? ''
  const status = params.get('status') ?? ''
  const page = Math.max(0, Number(params.get('page') ?? '0') || 0)

  const tenants = useQuery({
    queryKey: ['platform-tenants', { q, status, page }],
    queryFn: () => api.get<Page<PlatformTenant>>(`/platform/tenants?${toQuery({ q, status, page, size: SIZE })}`),
    placeholderData: keepPreviousData,
  })

  function onSearch(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const data = new FormData(event.currentTarget)
    const next = new URLSearchParams()
    for (const key of ['q', 'status']) {
      const value = data.get(key)
      if (typeof value === 'string' && value.trim()) next.set(key, value.trim())
    }
    setParams(next, { replace: true })
  }

  return (
    <>
      <PageHeader title="Workspaces" description="Every workspace on NexusOps." />
      <form onSubmit={onSearch} role="search" className="mb-4 flex flex-wrap items-end gap-3">
        <div className="space-y-1.5">
          <Label htmlFor="tenants-q">Search workspaces</Label>
          <Input id="tenants-q" name="q" defaultValue={q} placeholder="Name or URL" />
        </div>
        <div className="space-y-1.5">
          <Label htmlFor="tenants-status">Status</Label>
          <NativeSelect id="tenants-status" name="status" defaultValue={status}>
            <option value="">All statuses</option>
            <option value="ACTIVE">Active</option>
            <option value="SUSPENDED">Suspended</option>
            <option value="PENDING_VERIFICATION">Pending verification</option>
          </NativeSelect>
        </div>
        <Button type="submit" variant="outline">
          Search
        </Button>
      </form>

      {tenants.isPending ? (
        <ListSkeleton />
      ) : tenants.isError ? (
        <ErrorState error={tenants.error} onRetry={() => void tenants.refetch()} />
      ) : tenants.data.items.length === 0 ? (
        <EmptyState title="No workspaces match." description="Try a different search or status." />
      ) : (
        <>
          <div className="overflow-x-auto rounded-lg border">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Workspace</TableHead>
                  <TableHead>Status</TableHead>
                  <TableHead>Plan</TableHead>
                  <TableHead>Active users</TableHead>
                  <TableHead>Owners</TableHead>
                  <TableHead>Created</TableHead>
                  {canSuspend && <TableHead><span className="sr-only">Actions</span></TableHead>}
                </TableRow>
              </TableHeader>
              <TableBody>
                {tenants.data.items.map((tenant) => (
                  <TableRow key={tenant.id}>
                    <TableCell>
                      <div className="font-medium">{tenant.name}</div>
                      <div className="text-xs text-muted-foreground">{tenant.slug}</div>
                    </TableCell>
                    <TableCell>
                      <StatusBadge status={tenant.status} />
                    </TableCell>
                    <TableCell>{tenant.planCode}</TableCell>
                    <TableCell>{tenant.activeUsers}</TableCell>
                    <TableCell>
                      {tenant.ownerEmails.map((email) => (
                        <div key={email}>{email}</div>
                      ))}
                    </TableCell>
                    <TableCell className="whitespace-nowrap">{formatDateTime(tenant.createdAt)}</TableCell>
                    {canSuspend && (
                      <TableCell className="text-right">
                        {tenant.status === 'ACTIVE' && (
                          <Button variant="destructive" size="sm" aria-label={`Suspend ${tenant.name}`} onClick={() => setChange({ tenant, action: 'suspend' })}>
                            Suspend
                          </Button>
                        )}
                        {tenant.status === 'SUSPENDED' && (
                          <Button variant="outline" size="sm" aria-label={`Reactivate ${tenant.name}`} onClick={() => setChange({ tenant, action: 'reactivate' })}>
                            Reactivate
                          </Button>
                        )}
                      </TableCell>
                    )}
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
          <Pagination
            page={tenants.data.page}
            size={tenants.data.size}
            total={tenants.data.total}
            onPage={(p) => {
              const next = new URLSearchParams(params)
              next.set('page', String(p))
              setParams(next, { replace: true })
            }}
          />
        </>
      )}
      {change && <StatusChangeDialog key={change.tenant.id} tenant={change.tenant} action={change.action} onClose={() => setChange(null)} />}
    </>
  )
}
```

- [ ] **Step 5: Run the tests to verify they pass, then the full gate**

Run: `cd frontend && npm test`
Expected: PASS (all suites).

Run: `make test-frontend`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add frontend
git commit -m "feat(frontend): platform console with TOTP sign-in, workspace list and suspension"
```

---

### Task 11: End-to-end journeys on the real stack, CI E2E, docs

**Files:**
- Delete: `frontend/e2e/smoke.spec.ts` (it asserts the removed home page)
- Modify: `frontend/playwright.config.ts`
- Create: `frontend/e2e/global-setup.ts`
- Create: `frontend/e2e/support/totp.ts`
- Create: `frontend/e2e/support/mailpit.ts`
- Create: `frontend/e2e/support/workspace.ts`
- Create: `frontend/e2e/fixtures/platform-operator.sql`
- Create: `frontend/e2e/totp.spec.ts`
- Create: `frontend/e2e/tenant.spec.ts`
- Create: `frontend/e2e/platform.spec.ts`
- Modify: `Makefile` (`e2e` target)
- Modify: `infra/docker/docker-compose.yml` (backend `APP_BASE_URL`)
- Modify: `.github/workflows/ci.yml` (`e2e` job)
- Modify: `README.md`, `docs/superpowers/specs/2026-10-04-platform-foundation-design.md` (new §18), `frontend/tsconfig.node.json` (only if `e2e/**` isn't already type-checked)

**Interfaces:**
- Consumes: every UI text defined in Tasks 2–10, used verbatim as locators.
  - Labels: "Workspace name", "Workspace URL", "First name", "Last name", "Work email", "Password", "Email", "Repeat password", "Name", "Role", "Authenticator code", "Search workspaces", "Reason".
  - Buttons: "Create workspace", "Sign in", "New role", "Create role", "Save permissions", "Invite people", "Send invitation", "Save roles", "Suspend workspace", "Reactivate workspace".
  - Headings: "Check your email", "Email verified", "Welcome, …", "Join …", "You're in", "Workspaces".
  - Toasts: "Permissions saved.", "Invitation sent to ….", "Roles updated.", "… suspended."
- Produces: `make e2e`, which runs the whole journey suite against Docker Compose; CI runs the same target.

- [ ] **Step 1: Generate the platform operator fixture values**

The seed must contain:
- an Argon2 hash made by Spring's `Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()`;
- a TOTP secret encrypted with `TotpSecretCipher` under the **local** key `bG9jYWwtb25seS10b3RwLWtleS0zMi1ieXRlcyEhISE=` (committed and non-secret; see `application-local.yml`), with AAD = the operator id.

Generate both with a throwaway JUnit test in `backend/src/test/java/com/nexusops/E2eSeedValuesTest.java`. Do NOT commit it.

```java
package com.nexusops;

import com.nexusops.platform.totp.TotpSecretCipher;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

class E2eSeedValuesTest {
    @Test
    void print() {
        UUID id = UUID.fromString("01930000-0000-7000-8000-0000000e2e01");
        byte[] secret = new byte[20];
        // base32 JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP == "Hello!\xDE\xAD\xBE\xEF" twice
        byte[] half = {0x48, 0x65, 0x6c, 0x6c, 0x6f, 0x21, (byte) 0xde, (byte) 0xad, (byte) 0xbe, (byte) 0xef};
        System.arraycopy(half, 0, secret, 0, 10);
        System.arraycopy(half, 0, secret, 10, 10);
        System.out.println("HASH=" + Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8().encode("e2e platform passphrase 42"));
        System.out.println("ENC=" + new TotpSecretCipher("bG9jYWwtb25seS10b3RwLWtleS0zMi1ieXRlcyEhISE=").encrypt(secret, id));
        System.out.println("B32=" + com.nexusops.platform.totp.Totp.base32(secret));
    }
}
```

Run: `cd backend && ./gradlew test --tests 'com.nexusops.E2eSeedValuesTest' -i | grep -E '^(HASH|ENC|B32)='`
Expected: three lines. `B32=JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP`. Copy the values, then delete the test file.

`frontend/e2e/fixtures/platform-operator.sql`. Fill in `<HASH>` and `<ENC>` from the output above; these are the only two values you substitute.

```sql
-- E2E-only platform operator for the LOCAL compose stack. The secret is bound to the committed, non-secret local
-- PLATFORM_TOTP_KEY, so it is useless against any environment with a real key. Idempotent: re-running resets state.
SET app.platform_access = 'on';
INSERT INTO platform_users (id, email, password_hash, totp_secret_enc, totp_last_step, failed_totp_attempts,
                            role, status, token_version, created_at, updated_at)
VALUES ('01930000-0000-7000-8000-0000000e2e01', 'e2e-ops@nexusops.test', '<HASH>', '<ENC>', 0, 0,
        'PLATFORM_ADMIN', 'ACTIVE', 0, now(), now())
ON CONFLICT (id) DO UPDATE SET
    password_hash = EXCLUDED.password_hash,
    totp_secret_enc = EXCLUDED.totp_secret_enc,
    totp_last_step = 0,
    failed_totp_attempts = 0,
    role = 'PLATFORM_ADMIN',
    status = 'ACTIVE',
    updated_at = now();
```

- [ ] **Step 2: Write the support code and Playwright config**

`frontend/e2e/support/totp.ts`:

```ts
import { createHmac } from 'node:crypto'

const ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567'

export function base32Decode(encoded: string): Buffer {
  let buffer = 0
  let bits = 0
  const out: number[] = []
  for (const char of encoded.replace(/[\s=]/g, '').toUpperCase()) {
    buffer = (buffer << 5) | ALPHABET.indexOf(char)
    bits += 5
    if (bits >= 8) {
      out.push((buffer >> (bits - 8)) & 0xff)
      bits -= 8
    }
  }
  return Buffer.from(out)
}

/** RFC 6238 (SHA-1, 6 digits, 30 s), matching the backend's Totp. */
export function totp(base32Secret: string, at: number = Date.now()): string {
  const counter = Buffer.alloc(8)
  counter.writeBigUInt64BE(BigInt(Math.floor(at / 1000 / 30)))
  const hash = createHmac('sha1', base32Decode(base32Secret)).update(counter).digest()
  const offset = hash[hash.length - 1] & 0x0f
  const binary =
    ((hash[offset] & 0x7f) << 24) | (hash[offset + 1] << 16) | (hash[offset + 2] << 8) | hash[offset + 3]
  return String(binary % 1_000_000).padStart(6, '0')
}
```

`frontend/e2e/support/mailpit.ts`:

```ts
const MAILPIT = process.env.E2E_MAILPIT_URL ?? 'http://localhost:8025'

interface Summary {
  ID: string
}
interface Message {
  Text: string
  HTML: string
}

/** Polls Mailpit for the newest email to `to` containing `${path}?token=…` and returns the decoded token. */
export async function tokenFromEmail(to: string, path: '/verify-email' | '/invite/accept'): Promise<string> {
  const pattern = new RegExp(`${path.replace(/\//g, '\\/')}\\?token=([^\\s"'<&]+)`)
  const deadline = Date.now() + 30_000
  while (Date.now() < deadline) {
    const search = (await (
      await fetch(`${MAILPIT}/api/v1/search?query=${encodeURIComponent(`to:"${to}"`)}`)
    ).json()) as { messages?: Summary[] }
    for (const summary of search.messages ?? []) {
      const message = (await (await fetch(`${MAILPIT}/api/v1/message/${summary.ID}`)).json()) as Message
      const match = pattern.exec(`${message.Text}\n${message.HTML}`)
      if (match) return decodeURIComponent(match[1])
    }
    await new Promise((resolve) => setTimeout(resolve, 500))
  }
  throw new Error(`No ${path} email for ${to} within 30s`)
}
```

`frontend/e2e/support/workspace.ts`:

```ts
import { expect, type Page } from '@playwright/test'
import { tokenFromEmail } from './mailpit'

export const PASSWORD = 'e2e correct horse battery'

export interface Workspace {
  name: string
  slug: string
  email: string
}

export function newWorkspace(prefix: string): Workspace {
  const stamp = `${Date.now().toString(36)}${Math.floor(Math.random() * 1e4).toString(36)}`
  return { name: `E2E ${prefix} ${stamp}`, slug: `e2e-${prefix}-${stamp}`, email: `owner-${stamp}@e2e.test` }
}

export async function signUpAndVerify(page: Page, ws: Workspace): Promise<void> {
  await page.goto('/signup')
  await page.getByLabel('Workspace name').fill(ws.name)
  await page.getByLabel('Workspace URL').fill(ws.slug)
  await page.getByLabel('First name').fill('Ada')
  await page.getByLabel('Last name').fill('Owner')
  await page.getByLabel('Work email').fill(ws.email)
  await page.getByLabel('Password').fill(PASSWORD)
  await page.getByRole('button', { name: 'Create workspace' }).click()
  await expect(page.getByRole('heading', { name: 'Check your email' })).toBeVisible()
  const token = await tokenFromEmail(ws.email, '/verify-email')
  await page.goto(`/verify-email?token=${encodeURIComponent(token)}`)
  await expect(page.getByRole('heading', { name: 'Email verified' })).toBeVisible()
}

export async function signIn(page: Page, ws: Pick<Workspace, 'slug'>, email: string, password = PASSWORD) {
  await page.goto('/login')
  await page.getByLabel('Workspace URL').fill(ws.slug)
  await page.getByLabel('Email').fill(email)
  await page.getByLabel('Password').fill(password)
  await page.getByRole('button', { name: 'Sign in' }).click()
}
```

`frontend/e2e/global-setup.ts`:

```ts
import { execFileSync } from 'node:child_process'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

const BASE = process.env.E2E_BASE_URL ?? 'http://localhost:3000'
const COMPOSE_FILE = fileURLToPath(new URL('../../infra/docker/docker-compose.yml', import.meta.url))

/** Waits until nginx → backend answers (the refresh route returns 401 without a cookie), then seeds the operator. */
export default async function globalSetup() {
  const deadline = Date.now() + 180_000
  for (;;) {
    try {
      const response = await fetch(`${BASE}/api/v1/auth/refresh`, { method: 'POST' })
      if (response.status === 401) break
    } catch {
      // not up yet
    }
    if (Date.now() > deadline) throw new Error(`Backend behind ${BASE} not ready after 180s`)
    await new Promise((resolve) => setTimeout(resolve, 2000))
  }
  const sql = readFileSync(new URL('./fixtures/platform-operator.sql', import.meta.url), 'utf8')
  execFileSync(
    'docker',
    ['compose', '-f', COMPOSE_FILE, 'exec', '-T', 'postgres', 'psql', '-U', 'nexusops_owner', '-d', 'nexusops', '-v', 'ON_ERROR_STOP=1'],
    { input: sql, stdio: ['pipe', 'inherit', 'inherit'] },
  )
}
```

Replace `frontend/playwright.config.ts`:

```ts
import { defineConfig, devices } from '@playwright/test'

/**
 * Journeys run against the real stack: `make e2e` starts Docker Compose (UI :3000, API, Postgres, Redis, Mailpit)
 * and sets E2E_BASE_URL. Serial: the journeys share rate-limit buckets (one client IP behind nginx) and Mailpit.
 */
export default defineConfig({
  testDir: './e2e',
  globalSetup: './e2e/global-setup.ts',
  fullyParallel: false,
  workers: 1,
  timeout: 90_000,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? 'github' : 'list',
  use: { baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:3000', trace: 'on-first-retry' },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
})
```

Delete `frontend/e2e/smoke.spec.ts`.

- [ ] **Step 3: Write the journeys**

`frontend/e2e/totp.spec.ts`:

```ts
import { expect, test } from '@playwright/test'
import { totp } from './support/totp'

test('the TOTP helper matches RFC 6238', () => {
  expect(totp('GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ', 59_000)).toBe('287082')
  expect(totp('GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ', 1_111_111_109_000)).toBe('081804')
})
```

`frontend/e2e/tenant.spec.ts`:

```ts
import { expect, test } from '@playwright/test'
import { tokenFromEmail } from './support/mailpit'
import { newWorkspace, PASSWORD, signIn, signUpAndVerify } from './support/workspace'

test('an owner builds a team, and the audit log shows every step', async ({ page, browser }) => {
  const ws = newWorkspace('team')
  const invitee = `grace-${ws.slug}@e2e.test`

  await signUpAndVerify(page, ws)
  await signIn(page, ws, ws.email)
  await expect(page.getByRole('heading', { name: 'Welcome, Ada' })).toBeVisible()

  // A custom role that can see people.
  await page.goto('/app/settings/roles') // also proves a deep link restores the session from the refresh cookie
  await page.getByRole('button', { name: 'New role' }).click()
  await page.getByRole('dialog').getByLabel('Name').fill('Support agent')
  await page.getByRole('button', { name: 'Create role' }).click()
  await expect(page.getByRole('heading', { name: 'Support agent' })).toBeVisible()
  await page.getByRole('checkbox', { name: /View users/ }).check()
  await page.getByRole('button', { name: 'Save permissions' }).click()
  await expect(page.getByText('Permissions saved.')).toBeVisible()

  // Invite someone with it.
  await page.getByRole('navigation', { name: 'Settings' }).getByRole('link', { name: 'Users' }).click()
  await page.getByRole('button', { name: 'Invite people' }).click()
  const invite = page.getByRole('dialog')
  await invite.getByLabel('Email').fill(invitee)
  await invite.getByLabel('Role').selectOption({ label: 'Support agent' })
  await invite.getByRole('button', { name: 'Send invitation' }).click()
  await expect(page.getByText(`Invitation sent to ${invitee}.`)).toBeVisible()

  // The invitee accepts in their own browser and signs in.
  const token = await tokenFromEmail(invitee, '/invite/accept')
  const graceContext = await browser.newContext()
  const grace = await graceContext.newPage()
  await grace.goto(`/invite/accept?token=${encodeURIComponent(token)}`)
  await expect(grace.getByRole('heading', { name: `Join ${ws.name}` })).toBeVisible()
  await grace.getByLabel('First name').fill('Grace')
  await grace.getByLabel('Last name').fill('Hopper')
  await grace.getByLabel('Password', { exact: true }).fill(PASSWORD)
  await grace.getByLabel('Repeat password').fill(PASSWORD)
  await grace.getByRole('button', { name: `Join ${ws.name}` }).click()
  await expect(grace.getByRole('heading', { name: "You're in" })).toBeVisible()
  await grace.getByRole('link', { name: `Sign in to ${ws.name}` }).click()
  await grace.getByLabel('Password').fill(PASSWORD)
  await grace.getByRole('button', { name: 'Sign in' }).click()
  await expect(grace.getByRole('heading', { name: 'Welcome, Grace' })).toBeVisible()
  const graceNav = grace.getByRole('navigation', { name: 'Workspace' })
  await expect(graceNav.getByRole('link', { name: 'Settings' })).toBeVisible()
  await expect(graceNav.getByRole('link', { name: 'Audit' })).toHaveCount(0)

  // The owner gives Grace a second role that can read the audit log.
  await page.getByRole('navigation', { name: 'Settings' }).getByRole('link', { name: 'Roles' }).click()
  await page.getByRole('button', { name: 'New role' }).click()
  await page.getByRole('dialog').getByLabel('Name').fill('Auditor')
  await page.getByRole('button', { name: 'Create role' }).click()
  await page.getByRole('checkbox', { name: /View the audit log/ }).check()
  await page.getByRole('button', { name: 'Save permissions' }).click()
  await expect(page.getByText('Permissions saved.')).toBeVisible()
  await page.getByRole('navigation', { name: 'Settings' }).getByRole('link', { name: 'Users' }).click()
  await page.getByRole('button', { name: 'Manage Grace Hopper' }).click()
  await page.getByRole('dialog').getByRole('checkbox', { name: 'Auditor' }).check()
  await page.getByRole('dialog').getByRole('button', { name: 'Save roles' }).click()
  await expect(page.getByText('Roles updated.')).toBeVisible()

  // Grace sees the new section after a reload (permissions are resolved per request).
  await grace.reload()
  await expect(graceNav.getByRole('link', { name: 'Audit' })).toBeVisible()
  await graceContext.close()

  // The audit trail shows the journey.
  await page.keyboard.press('Escape')
  await page.getByRole('navigation', { name: 'Workspace' }).getByRole('link', { name: 'Audit' }).click()
  await expect(page.getByRole('heading', { name: 'Audit log' })).toBeVisible()
  for (const action of ['RoleCreated', 'RolePermissionsChanged', 'InvitationCreated', 'InvitationAccepted', 'UserRolesChanged']) {
    await expect(page.getByRole('cell', { name: action }).first()).toBeVisible()
  }
})
```

`frontend/e2e/platform.spec.ts`:

```ts
import { expect, test } from '@playwright/test'
import { totp } from './support/totp'
import { newWorkspace, signIn, signUpAndVerify } from './support/workspace'

const OPERATOR = { email: 'e2e-ops@nexusops.test', password: 'e2e platform passphrase 42', secret: 'JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP' }

test('a platform admin suspends and reactivates a workspace', async ({ page, browser }) => {
  const ws = newWorkspace('susp')
  await signUpAndVerify(page, ws)
  await signIn(page, ws, ws.email)
  await expect(page.getByRole('heading', { name: 'Welcome, Ada' })).toBeVisible()

  const opsContext = await browser.newContext()
  const ops = await opsContext.newPage()
  await ops.goto('/platform/login')
  await ops.getByLabel('Email').fill(OPERATOR.email)
  await ops.getByLabel('Password').fill(OPERATOR.password)
  await ops.getByLabel('Authenticator code').fill(totp(OPERATOR.secret))
  await ops.getByRole('button', { name: 'Sign in' }).click()
  await expect(ops.getByRole('heading', { name: 'Workspaces' })).toBeVisible()

  await ops.getByLabel('Search workspaces').fill(ws.slug)
  await ops.getByRole('button', { name: 'Search' }).click()
  await ops.getByRole('button', { name: `Suspend ${ws.name}` }).click()
  await ops.getByRole('dialog').getByLabel('Reason').fill('E2E suspension check')
  await ops.getByRole('button', { name: 'Suspend workspace' }).click()
  await expect(ops.getByText(`${ws.name} suspended.`)).toBeVisible()

  // The owner is out: a reload can't restore the session, and signing in names the reason.
  await page.reload()
  await expect(page.getByRole('heading', { name: 'Sign in' })).toBeVisible()
  await signIn(page, ws, ws.email)
  await expect(page.getByText('Workspace suspended.')).toBeVisible()

  await ops.getByRole('button', { name: `Reactivate ${ws.name}` }).click()
  await ops.getByRole('dialog').getByLabel('Reason').fill('E2E done')
  await ops.getByRole('button', { name: 'Reactivate workspace' }).click()
  await expect(ops.getByText(`${ws.name} reactivated.`)).toBeVisible()
  await opsContext.close()

  await page.getByRole('button', { name: 'Sign in' }).click()
  await expect(page.getByRole('heading', { name: 'Welcome, Ada' })).toBeVisible()
})
```

- [ ] **Step 4: Wire `make e2e`, compose and CI**

In `Makefile`, replace the `e2e` target:

```make
e2e:           ## Playwright journeys against the full Docker stack (UI :3000, Mailpit :8025); leaves it running
	$(COMPOSE) --profile app up -d --build --wait postgres redis mailpit backend frontend
	cd frontend && E2E_BASE_URL=http://localhost:3000 npm run e2e
```

In `infra/docker/docker-compose.yml`, add this to the `backend` service `environment`, so emailed links point at the containerised UI:

```yaml
      APP_BASE_URL: ${APP_BASE_URL:-http://localhost:3000}
```

In `.github/workflows/ci.yml`, replace the `e2e` job with the block below. Keep the action SHAs exactly as pinned elsewhere in the file.

```yaml
  e2e:
    if: github.event_name != 'pull_request'
    needs: [backend, frontend]
    runs-on: ubuntu-latest
    timeout-minutes: 30
    steps:
      - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1
      - uses: actions/setup-node@820762786026740c76f36085b0efc47a31fe5020 # v7.0.0
        with: { node-version: "22", cache: npm, cache-dependency-path: frontend/package-lock.json }
      - run: npm ci
        working-directory: frontend
      - run: npx playwright install --with-deps chromium
        working-directory: frontend
      - run: make e2e
      - if: failure()
        run: docker compose -f infra/docker/docker-compose.yml --profile app logs --no-color --tail 300 backend
```

- [ ] **Step 5: Run the journeys**

Run: `make e2e`
Expected: Compose builds and starts. Playwright then runs 3 tests (`totp.spec.ts`, `tenant.spec.ts`, `platform.spec.ts`), and all pass.

If `psql` inside the container asks for a password, pass `-e PGPASSWORD=<owner password>` to `docker compose exec`. Read the password from `process.env.DB_OWNER_PASSWORD`, defaulting to `nexusops_owner_local`, and record the change.

Then run `make e2e` a second time without tearing down. Expected: PASS again. This proves the seed is idempotent and the journeys use unique workspaces.

If a step fails on a locator, fix the *test* only when the UI text in Tasks 2–10 differs from the locator by accident. If the UI behaves wrongly, fix the UI with a unit test first (TDD), and record it under Deviations.

- [ ] **Step 6: Docs**

Append §18 to `docs/superpowers/specs/2026-10-04-platform-foundation-design.md`:

```markdown
## 18. Deltas adopted in Plan 5

1. **UI kit:** shadcn base-nova plus native select, checkbox and switch wrappers, which are accessible and testable in jsdom.
2. **Two in-memory sessions:**
   - tenant: `/auth/refresh`, `/me`;
   - platform: `/platform/auth/refresh`, `/platform/me`.

   Each restores once at boot (memoized against StrictMode double mounts). An unrecoverable 401 clears the session
   and the query cache. `?next=` honours only `/app…` and `/platform…`.
3. **Routes:** as in §11, plus `/app/settings/roles/:roleId` and `/platform` (redirects to `/platform/tenants`).
   Module placeholders name their blueprint phase: CRM 5, Inventory 6, HelpDesk 7, HRMS 8, Workflows 9, Insights 11,
   AI Assistant 12.
4. **Permission gating mirrors the server's `@PreAuthorize` exactly.** The UI hides; the server enforces.
5. **Emailed tokens leave the address bar immediately,** and the referrer policy is `no-referrer`. This closes the
   Plan 3 deferred item.
6. **E2E runs against Docker Compose:**
   - `make e2e` runs Playwright against :3000 and reads emails from Mailpit :8025;
   - a seeded, local-key-bound platform operator covers the staff journey;
   - CI runs the same target on `main` and on manual runs.
```

In `README.md`, replace the line "`make e2e` runs Playwright." with:

```markdown
- `make e2e` starts the full Docker stack (UI http://localhost:3000, Mailpit http://localhost:8025) and runs the
  Playwright journeys: sign up → verify → sign in → custom role → invite → accept → assign → audit trail, and a
  platform admin suspending and reactivating a workspace. The stack stays up afterwards; `make down` stops it.
```

Add a short **Using the app** section after "Quick start", covering:
- `make up && make backend` in one terminal and `make frontend` in another;
- open http://localhost:5173/signup;
- the staff console at `/platform/login`, after creating an operator with `make platform-admin`.

- [ ] **Step 7: Full verification**

Run: `make test`
Expected: PASS for test-infra, backend, frontend (format, lint, typecheck, test, build) and ai-service.

Run: `make e2e`
Expected: PASS (3 tests).

- [ ] **Step 8: Commit**

```bash
git add frontend Makefile infra/docker/docker-compose.yml .github/workflows/ci.yml README.md docs
git commit -m "test(e2e): tenant and platform journeys on the full stack; CI runs them; docs"
```
