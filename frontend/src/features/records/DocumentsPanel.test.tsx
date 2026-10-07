import { screen, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ALL_TENANT_PERMISSIONS } from '@/features/auth/permissions'
import { saveBlob } from '@/lib/download'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import { aDocument, aParty, pageOf } from '@/test/records'
import { renderApp } from '@/test/renderApp'

vi.mock('@/lib/download', () => ({ saveBlob: vi.fn() }))

function setup(permissions: string[] = [...ALL_TENANT_PERMISSIONS]) {
  const server = fakeServer()
  signedIn(server, testProfile({ permissions }))
    .on('GET /parties/:id', { body: aParty() })
    .on('GET /parties', { body: pageOf([]) })
    .on('GET /activities', { body: pageOf([]) })
    .on('GET /documents', { body: [aDocument()] })
    .on('GET /tasks', { body: pageOf([]) })
  return renderApp({ server, path: '/app/directory/p-acme' })
}

describe('DocumentsPanel', () => {
  beforeEach(() => vi.mocked(saveBlob).mockReset())

  it('lists documents with size and uploader', async () => {
    setup()
    const panel = within(await screen.findByRole('region', { name: 'Documents' }))
    expect(await panel.findByRole('button', { name: 'Download contract.pdf' })).toBeInTheDocument()
    expect(panel.getByText(/2.0 KB/)).toBeInTheDocument()
    expect(panel.getByText(/Ada Lovelace/)).toBeInTheDocument()
  })

  it('uploads a file for the subject', async () => {
    const { server, user } = setup()
    server.on('POST /documents', {
      status: 201,
      body: aDocument({ id: 'd-2', fileName: 'notes.txt' }),
    })
    const panel = within(await screen.findByRole('region', { name: 'Documents' }))
    await user.upload(
      panel.getByLabelText('Upload file'),
      new File(['hi'], 'notes.txt', { type: 'text/plain' }),
    )
    const form = server.callsTo('POST /documents')[0].form
    expect(form?.get('subjectType')).toBe('PARTY')
    expect(form?.get('subjectId')).toBe('p-acme')
    expect((form?.get('file') as File | null)?.name).toBe('notes.txt')
    expect(await screen.findByText('Uploaded notes.txt.')).toBeInTheDocument()
  })

  it('refuses files over 10 MB before uploading', async () => {
    const { server, user } = setup()
    const panel = within(await screen.findByRole('region', { name: 'Documents' }))
    const big = new File(['x'], 'big.bin')
    Object.defineProperty(big, 'size', { value: 10 * 1024 * 1024 + 1 })
    await user.upload(panel.getByLabelText('Upload file'), big)
    expect(await screen.findByText('The file is larger than 10 MB.')).toBeInTheDocument()
    expect(server.callsTo('POST /documents')).toHaveLength(0)
  })

  it('downloads with the server file name', async () => {
    const { server, user } = setup()
    server.on('GET /documents/:id/content', {
      raw: 'pdf-bytes',
      headers: {
        'Content-Type': 'application/pdf',
        'Content-Disposition': 'attachment; filename="contract.pdf"',
      },
    })
    const panel = within(await screen.findByRole('region', { name: 'Documents' }))
    await user.click(await panel.findByRole('button', { name: 'Download contract.pdf' }))
    await vi.waitFor(() => expect(saveBlob).toHaveBeenCalledTimes(1))
    expect(vi.mocked(saveBlob).mock.calls[0][1]).toBe('contract.pdf')
  })

  it('deletes after confirmation', async () => {
    const { server, user } = setup()
    server.on('DELETE /documents/:id', { status: 204 })
    const panel = within(await screen.findByRole('region', { name: 'Documents' }))
    await user.click(await panel.findByRole('button', { name: 'Delete contract.pdf' }))
    await user.click(
      within(await screen.findByRole('dialog')).getByRole('button', { name: 'Delete' }),
    )
    expect(server.callsTo('DELETE /documents/:id')[0].params.id).toBe('d-1')
  })

  it('is hidden without document permission and read-only without manage', async () => {
    setup(['directory.party.read'])
    await screen.findByRole('heading', { name: 'Acme' })
    expect(screen.queryByRole('region', { name: 'Documents' })).not.toBeInTheDocument()
  })
})
