import type { FieldValues, Path, UseFormReturn } from 'react-hook-form'
import { Textarea } from '@/components/ui/textarea'
import { describedBy, Field } from './Field'

/** A labelled react-hook-form textarea with accessible error wiring. */
export function TextAreaField<T extends FieldValues>({
  form,
  name,
  label,
  rows = 3,
  maxLength,
  hint,
}: {
  form: UseFormReturn<T>
  name: Path<T>
  label: string
  rows?: number
  maxLength?: number
  hint?: string
}) {
  const id = `field-${name}`
  const error = form.getFieldState(name, form.formState).error?.message
  return (
    <Field id={id} label={label} error={error} hint={hint}>
      <Textarea
        id={id}
        rows={rows}
        maxLength={maxLength}
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy(id, error, hint)}
        {...form.register(name)}
      />
    </Field>
  )
}
