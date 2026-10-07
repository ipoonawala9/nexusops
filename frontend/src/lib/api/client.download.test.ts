import { describe, expect, it } from 'vitest'
import { createApiClient, fileNameFrom } from './client'
import { createMemoryTokenStore } from './tokenStore'
import { fakeServer } from '@/test/fakeServer'

function client(server: ReturnType<typeof fakeServer>) {
  const tokens = createMemoryTokenStore()
  tokens.set('t-1')
  return createApiClient({
    baseUrl: '/api/v1',
    tokens,
    refresh: async () => null,
    fetchImpl: server.fetchImpl,
  })
}

describe('uploads and downloads', () => {
  it('sends FormData without forcing a JSON content type', async () => {
    const server = fakeServer({ 'POST /documents': { status: 201, body: { id: 'd-1' } } })
    const form = new FormData()
    form.set('subjectType', 'PARTY')
    form.set('file', new File(['hello'], 'hello.txt', { type: 'text/plain' }))
    await expect(client(server).upload('/documents', form)).resolves.toEqual({ id: 'd-1' })
    const call = server.callsTo('POST /documents')[0]
    expect(call.form?.get('subjectType')).toBe('PARTY')
    expect(call.headers.get('Content-Type')).toBeNull()
    expect(call.headers.get('Authorization')).toBe('Bearer t-1')
  })

  it('downloads a blob with the server file name', async () => {
    const server = fakeServer({
      'GET /documents/d-1/content': {
        raw: 'hello',
        headers: {
          'Content-Type': 'text/plain',
          'Content-Disposition':
            'attachment; filename="report.txt"; filename*=UTF-8\'\'r%C3%A9port.txt',
        },
      },
    })
    const file = await client(server).download('/documents/d-1/content')
    expect(file.fileName).toBe('réport.txt')
    expect(await file.blob.text()).toBe('hello')
    expect(server.callsTo('GET /documents/d-1/content')[0].headers.get('Accept')).toBe('*/*')
  })

  it('turns a failed download into an ApiError', async () => {
    const server = fakeServer({
      'GET /documents/d-1/content': { status: 404, body: { detail: 'Record not found.' } },
    })
    await expect(client(server).download('/documents/d-1/content')).rejects.toMatchObject({
      status: 404,
    })
  })

  it('parses content-disposition variants', () => {
    expect(fileNameFrom('attachment; filename="a b.pdf"')).toBe('a b.pdf')
    expect(fileNameFrom('attachment; filename=plain.txt')).toBe('plain.txt')
    expect(fileNameFrom(null)).toBe('download')
  })
})
