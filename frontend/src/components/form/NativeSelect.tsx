import { forwardRef, type SelectHTMLAttributes } from 'react'
import { cn } from '@/lib/utils'

/** A native <select> styled like Input: accessible, keyboard-friendly and testable in jsdom. */
export const NativeSelect = forwardRef<HTMLSelectElement, SelectHTMLAttributes<HTMLSelectElement>>(
  function NativeSelect({ className, ...props }, ref) {
    return (
      <select
        ref={ref}
        className={cn(
          'h-8 w-full rounded-lg border border-input bg-background px-2.5 text-sm outline-none',
          'focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50',
          'aria-invalid:border-destructive disabled:opacity-50',
          className,
        )}
        {...props}
      />
    )
  },
)
