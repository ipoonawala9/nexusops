import { execFileSync } from 'node:child_process'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

const BASE = process.env.E2E_BASE_URL ?? 'http://localhost:3000'
const COMPOSE_FILE = fileURLToPath(
  new URL('../../infra/docker/docker-compose.yml', import.meta.url),
)

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
    [
      'compose',
      '-f',
      COMPOSE_FILE,
      'exec',
      '-T',
      'postgres',
      'psql',
      '-U',
      'nexusops_owner',
      '-d',
      'nexusops',
      '-v',
      'ON_ERROR_STOP=1',
    ],
    { input: sql, stdio: ['pipe', 'inherit', 'inherit'] },
  )
}
