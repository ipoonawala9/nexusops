import { screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import { aCategory, anArticle } from '@/test/records'
import { renderApp } from '@/test/renderApp'
import type { ArticleView } from '@/lib/api/types'

function setup(article: ArticleView = anArticle(), permissions?: string[]) {
  const server = fakeServer()
  signedIn(server, testProfile({ modules: ['HELPDESK'], ...(permissions ? { permissions } : {}) }))
    .on('GET /helpdesk/articles/:id', { body: article })
    .on('GET /helpdesk/categories', { body: [aCategory()] })
    .on('GET /documents', { body: [] })
  return renderApp({ server, path: `/app/helpdesk/articles/${article.id}` })
}

describe('ArticleDetailPage', () => {
  it('shows the article', async () => {
    setup()
    expect(await screen.findByRole('heading', { name: 'Clearing a paper jam' })).toBeInTheDocument()
    expect(
      screen.getByText('Open the rear tray and pull the sheet out gently.'),
    ).toBeInTheDocument()
    expect(screen.getByText('Published')).toBeInTheDocument()
  })

  it('publishes a draft', async () => {
    const { server, user } = setup(anArticle({ status: 'DRAFT', publishedAt: null, version: 0 }))
    const published = anArticle({ version: 1 })
    const publish = await screen.findByRole('button', { name: 'Publish' })
    server
      .on('POST /helpdesk/articles/:id/publish', { body: published })
      .on('GET /helpdesk/articles/:id', { body: published })
    await user.click(publish)
    await waitFor(() =>
      expect(server.callsTo('POST /helpdesk/articles/:id/publish')[0]?.body).toEqual({
        version: 0,
      }),
    )
    expect(await screen.findByRole('button', { name: 'Unpublish' })).toBeInTheDocument()
  })

  it('unpublishes a published article', async () => {
    const { server, user } = setup()
    const draft = anArticle({ status: 'DRAFT', publishedAt: null, version: 2 })
    const unpublish = await screen.findByRole('button', { name: 'Unpublish' })
    server
      .on('POST /helpdesk/articles/:id/unpublish', { body: draft })
      .on('GET /helpdesk/articles/:id', { body: draft })
    await user.click(unpublish)
    await waitFor(() =>
      expect(server.callsTo('POST /helpdesk/articles/:id/unpublish')[0]?.body).toEqual({
        version: 1,
      }),
    )
    expect(await screen.findByRole('button', { name: 'Publish' })).toBeInTheDocument()
  })

  it('archives after confirmation, and an archived article has no actions', async () => {
    const { server, user } = setup()
    const archived = anArticle({ status: 'ARCHIVED', version: 2 })
    const archive = await screen.findByRole('button', { name: 'Archive' })
    server
      .on('POST /helpdesk/articles/:id/archive', { body: archived })
      .on('GET /helpdesk/articles/:id', { body: archived })
    await user.click(archive)
    await user.click(
      within(await screen.findByRole('dialog')).getByRole('button', { name: 'Archive article' }),
    )
    await waitFor(() =>
      expect(server.callsTo('POST /helpdesk/articles/:id/archive')[0]?.body).toEqual({
        version: 1,
      }),
    )
    expect(await screen.findByText('Archived')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Edit' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Publish' })).not.toBeInTheDocument()
  })

  it('edits the article', async () => {
    const { server, user } = setup()
    const edited = anArticle({ title: 'Clearing any paper jam', version: 2 })
    const edit = await screen.findByRole('button', { name: 'Edit' })
    server
      .on('PUT /helpdesk/articles/:id', { body: edited })
      .on('GET /helpdesk/articles/:id', { body: edited })
    await user.click(edit)
    const dialog = await screen.findByRole('dialog')
    const title = within(dialog).getByLabelText('Title')
    await user.clear(title)
    await user.type(title, 'Clearing any paper jam')
    await user.click(within(dialog).getByRole('button', { name: 'Save changes' }))
    await waitFor(() =>
      expect(server.callsTo('PUT /helpdesk/articles/:id')[0]?.body).toEqual({
        title: 'Clearing any paper jam',
        body: 'Open the rear tray and pull the sheet out gently.',
        categoryId: 'c-general',
        version: 1,
      }),
    )
    expect(
      await screen.findByRole('heading', { name: 'Clearing any paper jam' }),
    ).toBeInTheDocument()
  })

  it('shows readers no actions', async () => {
    setup(anArticle(), ['helpdesk.article.read'])
    await screen.findByRole('heading', { name: 'Clearing a paper jam' })
    expect(screen.queryByRole('button', { name: 'Edit' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Unpublish' })).not.toBeInTheDocument()
  })
})
