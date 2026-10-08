import { useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { FormError } from '@/components/form/FormError'
import { useApi } from '@/lib/api/ApiContext'
import { ApiError } from '@/lib/api/client'
import { problemMessage } from '@/lib/api/problems'
import type { ImportResult } from '@/lib/api/types'

interface RowError {
  row: number
  field: string
  message: string
}

const COLUMNS =
  'first_name, last_name, company, job_title, email, phone, source, estimated_value, currency, description'

/** D11: all or nothing. Row errors are listed so the user can fix the spreadsheet and upload again. */
export function LeadImportDialog({ onClose }: { onClose: () => void }) {
  const api = useApi()
  const queryClient = useQueryClient()
  const [file, setFile] = useState<File | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [rows, setRows] = useState<RowError[]>([])
  const [errorCount, setErrorCount] = useState(0)

  async function submit() {
    if (!file) return
    setBusy(true)
    setError(null)
    setRows([])
    const form = new FormData()
    form.append('file', file)
    try {
      const result = await api.upload<ImportResult>('/leads/import', form)
      await queryClient.invalidateQueries({ queryKey: ['leads'] })
      toast.success(`${result.imported} ${result.imported === 1 ? 'lead' : 'leads'} imported.`)
      onClose()
    } catch (e) {
      const problem =
        e instanceof ApiError ? (e.problem as { rows?: RowError[]; errorCount?: number }) : null
      if (problem?.rows?.length) {
        setRows(problem.rows)
        setErrorCount(problem.errorCount ?? problem.rows.length)
      }
      setError(problemMessage(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Dialog
      open
      onOpenChange={(open) => {
        if (!open) onClose()
      }}
    >
      <DialogContent className="max-h-[90vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle>Import leads</DialogTitle>
          <DialogDescription>
            A UTF-8 CSV file with a header row, up to 500 leads and 256 KB. Columns: {COLUMNS}.
            Nothing is imported if any row has a problem.
          </DialogDescription>
        </DialogHeader>
        <div className="space-y-1.5">
          <Label htmlFor="lead-import-file">CSV file</Label>
          <Input
            id="lead-import-file"
            type="file"
            accept=".csv,text/csv"
            onChange={(e) => {
              setFile(e.target.files?.[0] ?? null)
              setError(null)
              setRows([])
            }}
          />
        </div>
        <FormError message={error} />
        {rows.length > 0 && (
          <div className="space-y-2">
            {errorCount > rows.length && (
              <p className="text-sm text-muted-foreground">
                Showing the first {rows.length} of {errorCount} problems.
              </p>
            )}
            <Table aria-label="Rows to fix">
              <TableHeader>
                <TableRow>
                  <TableHead>Row</TableHead>
                  <TableHead>Column</TableHead>
                  <TableHead>Problem</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {rows.map((r, i) => (
                  <TableRow key={`${r.row}-${r.field}-${i}`}>
                    <TableCell>{r.row}</TableCell>
                    <TableCell className="font-mono text-xs">{r.field}</TableCell>
                    <TableCell>{r.message}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
        )}
        <DialogFooter>
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button onClick={() => void submit()} disabled={!file || busy}>
            Import
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
