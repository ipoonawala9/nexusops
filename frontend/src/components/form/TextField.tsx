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
  inputMode?: 'text' | 'numeric' | 'decimal' | 'email'
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
          onChange: onValueChange
            ? (event) => onValueChange(String(event.target.value))
            : undefined,
        })}
      />
    </Field>
  )
}
