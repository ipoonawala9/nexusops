import { zodResolver } from '@hookform/resolvers/zod'
import { useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
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
import { TextAreaField } from '@/components/form/TextAreaField'
import { TextField } from '@/components/form/TextField'
import { requiredText } from '@/features/auth/schemas'
import { useApi } from '@/lib/api/ApiContext'
import { ApiError } from '@/lib/api/client'
import { applyFieldErrors, problemMessage } from '@/lib/api/problems'
import type { ArticleView } from '@/lib/api/types'
import { CategorySelect } from './CategorySelect'
import { invalidateHelpDesk } from './invalidation'

const schema = z.object({
  title: requiredText(200),
  body: requiredText(50000),
  categoryId: z.string(),
})
type Values = z.infer<typeof schema>
const FIELDS = ['title', 'body', 'categoryId'] as const

/** Write a new draft, or edit an article. Editing a published article keeps it published. */
export function ArticleFormDialog({
  article,
  onClose,
  onSaved,
}: {
  article?: ArticleView
  onClose: () => void
  onSaved: (saved: ArticleView) => void
}) {
  const api = useApi()
  const queryClient = useQueryClient()
  const [formError, setFormError] = useState<string | null>(null)
  const form = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: {
      title: article?.title ?? '',
      body: article?.body ?? '',
      categoryId: article?.category?.id ?? '',
    },
  })
  const onSubmit = form.handleSubmit(async (values) => {
    setFormError(null)
    const body = {
      title: values.title.trim(),
      body: values.body.trim(),
      categoryId: values.categoryId || null,
      ...(article ? { version: article.version } : {}),
    }
    try {
      const saved = article
        ? await api.put<ArticleView>(`/helpdesk/articles/${article.id}`, body)
        : await api.post<ArticleView>('/helpdesk/articles', body)
      queryClient.setQueryData(['helpdesk', 'article', saved.id], saved)
      await invalidateHelpDesk(queryClient)
      toast.success(article ? 'Changes saved.' : 'Draft saved.')
      onSaved(saved)
    } catch (error) {
      if (article && error instanceof ApiError && error.status === 409)
        void queryClient.invalidateQueries({ queryKey: ['helpdesk', 'article', article.id] })
      if (!applyFieldErrors(error, form.setError, FIELDS)) setFormError(problemMessage(error))
    }
  })
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>{article ? `Edit ${article.title}` : 'New article'}</DialogTitle>
          <DialogDescription>
            Plain text. Write it the way you&apos;d explain it to a customer on the phone.
          </DialogDescription>
        </DialogHeader>
        <form noValidate onSubmit={(e) => void onSubmit(e)} className="space-y-4">
          <TextField form={form} name="title" label="Title" maxLength={200} />
          <CategorySelect
            id="field-categoryId"
            label="Category"
            value={form.watch('categoryId')}
            current={article?.category}
            error={form.formState.errors.categoryId?.message}
            onChange={(id) => form.setValue('categoryId', id)}
          />
          <TextAreaField form={form} name="body" label="Article" rows={12} maxLength={50000} />
          <FormError message={formError} />
          <DialogFooter>
            <Button type="button" variant="ghost" onClick={onClose}>
              Cancel
            </Button>
            <Button type="submit" disabled={form.formState.isSubmitting}>
              {article ? 'Save changes' : 'Save draft'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
