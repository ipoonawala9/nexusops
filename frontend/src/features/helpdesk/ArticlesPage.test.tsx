import { act, screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import { aCategory, anArticle, anArticleSummary, pageOf } from '@/test/records'
import { renderApp } from '@/test/renderApp'

function setup(path = '/app/helpdesk/articles', permissions?: string[]) {
  const server = fakeServer()
  signedIn(server, testProfile({ modules: ['HELPDESK'], ...(permissions ? { permissions } : {}) }))
    .on('GET /helpdesk/articles', {
      body: pageOf([
        anArticleSummary(),
        anArticleSummary({
          id: 'a-2',
          title: 'GST invoices explained',
          status: 'DRAFT',
          publishedAt: null,
          category: null,
        }),
      ]),
    })
    .on('GET /helpdesk/articles/:id', { body: anArticle({ id: 'a-new', status: 'DRAFT' }) })
    .on('GET /helpdesk/categories', { body: [aCategory()] })
    .on('GET /documents', { body: [] })
  return renderApp({ server, path })
}

describe('ArticlesPage', () => {
  it('lists articles with category, status and excerpt', async () => {
    setup()
    const first = (await screen.findByRole('link', { name: 'Clearing a paper jam' })).closest(
      'li',
    ) as HTMLElement
    expect(first).toHaveTextContent('General')
    expect(first).toHaveTextContent('Open the rear tray')
    const draft = screen
      .getByRole('link', { name: 'GST invoices explained' })
      .closest('li') as HTMLElement
    expect(within(draft).getByText('Draft')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Clearing a paper jam' })).toHaveAttribute(
      'href',
      '/app/helpdesk/articles/a-1',
    )
  })

  it("shows the URL's search words in the search box", async () => {
    const { router } = setup('/app/helpdesk/articles?q=jam')
    expect(await screen.findByLabelText('Search articles')).toHaveValue('jam')
    await act(() => router.navigate('/app/helpdesk/articles?q=toner'))
    await waitFor(() => expect(screen.getByLabelText('Search articles')).toHaveValue('toner'))
  })

  it('searches and filters by status and category', async () => {
    const { server, user } = setup()
    await screen.findByRole('link', { name: 'Clearing a paper jam' })
    await user.selectOptions(screen.getByLabelText('Status'), 'ARCHIVED')
    await user.selectOptions(screen.getByLabelText('Category'), 'c-general')
    await user.type(screen.getByLabelText('Search articles'), 'paper jam')
    await user.click(screen.getByRole('button', { name: 'Search' }))
    await waitFor(() => {
      const last = server.callsTo('GET /helpdesk/articles').at(-1)?.query
      expect(last?.get('status')).toBe('ARCHIVED')
      expect(last?.get('categoryId')).toBe('c-general')
      expect(last?.get('q')).toBe('paper jam')
    })
  })

  it('writes a draft and opens it', async () => {
    const { server, user, router } = setup()
    server.on('POST /helpdesk/articles', {
      status: 201,
      body: anArticle({ id: 'a-new', status: 'DRAFT' }),
    })
    await user.click(await screen.findByRole('button', { name: 'New article' }))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByLabelText('Title'), 'Clearing a paper jam')
    await user.selectOptions(within(dialog).getByLabelText('Category'), 'c-general')
    await user.type(within(dialog).getByLabelText('Article'), 'Open the rear tray.')
    await user.click(within(dialog).getByRole('button', { name: 'Save draft' }))
    await waitFor(() => expect(server.callsTo('POST /helpdesk/articles')).toHaveLength(1))
    expect(server.callsTo('POST /helpdesk/articles')[0].body).toEqual({
      title: 'Clearing a paper jam',
      body: 'Open the rear tray.',
      categoryId: 'c-general',
    })
    await waitFor(() => expect(router.state.location.pathname).toBe('/app/helpdesk/articles/a-new'))
  })

  it('does not ask for categories when the editor may not read tickets', async () => {
    const { server, user } = setup('/app/helpdesk/articles', [
      'helpdesk.article.read',
      'helpdesk.article.manage',
    ])
    await user.click(await screen.findByRole('button', { name: 'New article' }))
    await screen.findByRole('dialog')
    expect(server.callsTo('GET /helpdesk/categories')).toHaveLength(0)
  })

  it('needs a title and a text', async () => {
    const { server, user } = setup()
    await user.click(await screen.findByRole('button', { name: 'New article' }))
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('button', { name: 'Save draft' }))
    expect(await within(dialog).findAllByText('Required.')).toHaveLength(2)
    expect(server.callsTo('POST /helpdesk/articles')).toHaveLength(0)
  })

  it('shows readers published articles only, without status filter or New article', async () => {
    setup('/app/helpdesk/articles', ['helpdesk.article.read'])
    await screen.findByRole('link', { name: 'Clearing a paper jam' })
    expect(screen.queryByLabelText('Status')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'New article' })).not.toBeInTheDocument()
  })
})
