import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { PermissionView } from '@/lib/api/types'
import { PermissionMatrix } from './PermissionMatrix'

const p = (code: string, module: string | null = null): PermissionView => ({
  code,
  module,
  description: code,
  moduleEnabled: module === null,
})

describe('PermissionMatrix', () => {
  it('groups foundation permissions by area, then modules', () => {
    render(
      <PermissionMatrix
        catalog={[
          p('crm.customer.read', 'CRM'),
          p('collaboration.task.read'),
          p('identity.user.read'),
          p('catalog.product.read'),
          p('directory.party.read'),
        ]}
        selected={new Set()}
        disabled={false}
        onToggle={vi.fn()}
      />,
    )
    const legends = screen.getAllByRole('group').map((group) => group.querySelector('legend')?.textContent)
    expect(legends).toEqual([
      'Workspace administration',
      'Directory',
      'Products',
      'Activities, tasks and documents',
      'CRM module (not enabled)',
    ])
  })
})
