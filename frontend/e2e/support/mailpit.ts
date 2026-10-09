const MAILPIT = process.env.E2E_MAILPIT_URL ?? 'http://localhost:8025'

interface Summary {
  ID: string
  Subject: string
}
interface Message {
  Text: string
  HTML: string
}

/** Polls Mailpit for the newest email to `to` containing `${path}?token=…` and returns the decoded token. */
export async function tokenFromEmail(
  to: string,
  path: '/verify-email' | '/invite/accept' | '/reset-password',
): Promise<string> {
  const pattern = new RegExp(`${path.replace(/\//g, '\\/')}\\?token=([^\\s"'<&]+)`)
  const deadline = Date.now() + 30_000
  while (Date.now() < deadline) {
    const search = (await (
      await fetch(`${MAILPIT}/api/v1/search?query=${encodeURIComponent(`to:"${to}"`)}`)
    ).json()) as { messages?: Summary[] }
    for (const summary of search.messages ?? []) {
      const message = (await (
        await fetch(`${MAILPIT}/api/v1/message/${summary.ID}`)
      ).json()) as Message
      const match = pattern.exec(`${message.Text}\n${message.HTML}`)
      if (match) return decodeURIComponent(match[1])
    }
    await new Promise((resolve) => setTimeout(resolve, 500))
  }
  throw new Error(`No ${path} email for ${to} within 30s`)
}

/** Polls Mailpit for the newest email to `to` whose subject contains `subjectContains`; returns its text body. */
export async function emailText(to: string, subjectContains: string): Promise<string> {
  const deadline = Date.now() + 30_000
  while (Date.now() < deadline) {
    const search = (await (
      await fetch(`${MAILPIT}/api/v1/search?query=${encodeURIComponent(`to:"${to}"`)}`)
    ).json()) as { messages?: Summary[] }
    const found = (search.messages ?? []).find((m) => m.Subject.includes(subjectContains))
    if (found) {
      const message = (await (
        await fetch(`${MAILPIT}/api/v1/message/${found.ID}`)
      ).json()) as Message
      return message.Text
    }
    await new Promise((resolve) => setTimeout(resolve, 500))
  }
  throw new Error(`No email to ${to} with "${subjectContains}" within 30s`)
}
