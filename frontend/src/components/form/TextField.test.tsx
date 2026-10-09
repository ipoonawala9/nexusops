import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useForm } from 'react-hook-form'
import { describe, expect, it } from 'vitest'
import { TextField } from './TextField'

function PasswordForm({ label = 'Password' }: { label?: string }) {
  const form = useForm<{ password: string }>({ defaultValues: { password: '' } })
  return <TextField form={form} name="password" label={label} type="password" />
}

describe('TextField password visibility', () => {
  it('hides the password until the toggle is pressed, then hides it again', async () => {
    const user = userEvent.setup()
    render(<PasswordForm />)
    const input = screen.getByLabelText('Password')
    await user.type(input, 'correct horse battery')
    expect(input).toHaveAttribute('type', 'password')

    const toggle = screen.getByRole('button', { name: 'Show password' })
    expect(toggle).toHaveAttribute('aria-pressed', 'false')
    await user.click(toggle)
    expect(input).toHaveAttribute('type', 'text')
    expect(input).toHaveValue('correct horse battery')
    expect(screen.getByRole('button', { name: 'Hide password' })).toHaveAttribute(
      'aria-pressed',
      'true',
    )

    await user.click(screen.getByRole('button', { name: 'Hide password' }))
    expect(input).toHaveAttribute('type', 'password')
  })

  it('names the toggle after its field and never submits the form', async () => {
    const user = userEvent.setup()
    let submitted = false
    render(
      <form
        onSubmit={(e) => {
          e.preventDefault()
          submitted = true
        }}
      >
        <PasswordForm label="Repeat password" />
      </form>,
    )
    await user.click(screen.getByRole('button', { name: 'Show repeat password' }))
    expect(submitted).toBe(false)
    expect(screen.getByLabelText('Repeat password')).toHaveAttribute('type', 'text')
  })

  it('adds no toggle to other fields', () => {
    function EmailForm() {
      const form = useForm<{ email: string }>({ defaultValues: { email: '' } })
      return <TextField form={form} name="email" label="Email" type="email" />
    }
    render(<EmailForm />)
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
  })
})
