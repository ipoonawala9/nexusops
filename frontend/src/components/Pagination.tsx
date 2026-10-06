import { Button } from '@/components/ui/button'

export function Pagination({
  page,
  size,
  total,
  onPage,
}: {
  page: number
  size: number
  total: number
  onPage: (page: number) => void
}) {
  const pages = Math.max(1, Math.ceil(total / size))
  if (total <= size && page === 0) return null
  return (
    <nav aria-label="Pagination" className="mt-4 flex items-center justify-between text-sm">
      <span>
        Page {page + 1} of {pages}
      </span>
      <div className="flex gap-2">
        <Button
          variant="outline"
          size="sm"
          disabled={page === 0}
          onClick={() => onPage(page - 1)}
          aria-label="Previous page"
        >
          Previous
        </Button>
        <Button
          variant="outline"
          size="sm"
          disabled={page + 1 >= pages}
          onClick={() => onPage(page + 1)}
          aria-label="Next page"
        >
          Next
        </Button>
      </div>
    </nav>
  )
}
