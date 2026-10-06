import { forwardRef, type InputHTMLAttributes } from 'react'
import { cn } from '@/lib/utils'

/** A native checkbox; pass role="switch" for on/off toggles. */
export const Checkbox = forwardRef<
  HTMLInputElement,
  Omit<InputHTMLAttributes<HTMLInputElement>, 'type'>
>(function Checkbox({ className, ...props }, ref) {
  return (
    <input
      ref={ref}
      type="checkbox"
      className={cn(
        'size-4 shrink-0 rounded border-input accent-primary disabled:opacity-50',
        className,
      )}
      {...props}
    />
  )
})
