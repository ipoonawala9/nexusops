import { zodResolver } from '@hookform/resolvers/zod'
import { useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { useNavigate } from 'react-router'
import { toast } from 'sonner'
import { z } from 'zod'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { FormError } from '@/components/form/FormError'
import { TextField } from '@/components/form/TextField'
import { requiredText } from '@/features/auth/schemas'
import { useApi } from '@/lib/api/ApiContext'
import { applyFieldErrors, problemMessage } from '@/lib/api/problems'
import type { RoleView } from '@/lib/api/types'

const schema = z.object({
  name: requiredText(60),
  description: z.string().trim().max(255, 'Use at most 255 characters.'), // server: RoleDtos @Size(max = 255), AuthorizationService
})
type Values = z.infer<typeof schema>

export function CreateRoleDialog({ onClose }: { onClose: () => void }) {
  const api = useApi()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [formError, setFormError] = useState<string | null>(null)
  const form = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: { name: '', description: '' },
  })

  const submit = form.handleSubmit(async (values) => {
    setFormError(null)
    try {
      const role = await api.post<RoleView>('/roles', { ...values, permissions: [] })
      await queryClient.invalidateQueries({ queryKey: ['roles'] })
      toast.success('Role created. Now choose its permissions.')
      onClose()
      navigate(`/app/settings/roles/${role.id}`)
    } catch (error) {
      if (!applyFieldErrors(error, form.setError, ['name', 'description'] as const))
        setFormError(problemMessage(error))
    }
  })

  return (
    <Dialog
      open
      onOpenChange={(open) => {
        if (!open) onClose()
      }}
    >
      <DialogContent>
        <DialogHeader>
          <DialogTitle>New role</DialogTitle>
          <DialogDescription>
            Name it after a job, e.g. "Support agent". You'll pick permissions next.
          </DialogDescription>
        </DialogHeader>
        <form noValidate onSubmit={submit} className="space-y-4">
          <TextField form={form} name="name" label="Name" />
          <TextField form={form} name="description" label="Description" />
          <FormError message={formError} />
          <DialogFooter>
            <Button type="button" variant="ghost" onClick={onClose}>
              Cancel
            </Button>
            <Button type="submit" disabled={form.formState.isSubmitting}>
              Create role
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
