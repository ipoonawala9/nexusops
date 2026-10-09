import { Eye, EyeOff } from 'lucide-react'
import { useState } from 'react'
import type { FieldValues, Path, UseFormReturn } from 'react-hook-form'
import { Input } from '@/components/ui/input'
import { describedBy, Field } from './Field'

/** A labelled react-hook-form text input with accessible error wiring. Password fields get a show/hide toggle. */
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
  inputMode?: 'text' | 'numeric' | 'decimal' | 'email'
  maxLength?: number
  onValueChange?: (value: string) => void
}) {
  const id = `field-${name}`
  const error = form.getFieldState(name, form.formState).error?.message
  const [visible, setVisible] = useState(false)
  const isPassword = type === 'password'
  const input = (
    <Input
      id={id}
      type={isPassword && visible ? 'text' : type}
      autoComplete={autoComplete}
      inputMode={inputMode}
      maxLength={maxLength}
      className={isPassword ? 'pr-10' : undefined}
      aria-invalid={error ? true : undefined}
      aria-describedby={describedBy(id, error, hint)}
      {...form.register(name, {
        onChange: onValueChange ? (event) => onValueChange(String(event.target.value)) : undefined,
      })}
    />
  )
  return (
    <Field id={id} label={label} error={error} hint={hint}>
      {isPassword ? (
        <div className="relative">
          {input}
          <button
            type="button"
            aria-label={`${visible ? 'Hide' : 'Show'} ${label.toLowerCase()}`}
            aria-pressed={visible}
            aria-controls={id}
            onClick={() => setVisible((v) => !v)}
            className="absolute inset-y-0 right-0 flex w-10 items-center justify-center rounded-r-md text-muted-foreground hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
          >
            {visible ? (
              <EyeOff className="size-4" aria-hidden="true" />
            ) : (
              <Eye className="size-4" aria-hidden="true" />
            )}
          </button>
        </div>
      ) : (
        input
      )}
    </Field>
  )
}
