import { useState, type FormEvent } from 'react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { PERMISSIONS, useCan } from '@/features/auth/permissions'
import { useApi } from '@/lib/api/ApiContext'
import { problemMessage } from '@/lib/api/problems'
import type { PartyRoleType, PartyRoleView, PartyView, RoleStatus } from '@/lib/api/types'
import { formatDate } from '@/lib/format'
import { ROLE_LABELS } from './labels'

/** CUSTOMER and SUPPLIER need directory.party.manage; EMPLOYEE is a person-only role behind the employee codes. */
export function PartyRolesCard({ party, onChange }: { party: PartyView; onChange: (updated: PartyView) => void }) {
  const api = useApi()
  const can = useCan()
  const [busy, setBusy] = useState(false)
  const archived = party.archivedAt !== null
  const showEmployee = party.kind === 'PERSON' && can(PERMISSIONS.employeeRead)
  const roles: PartyRoleType[] = showEmployee ? ['CUSTOMER', 'SUPPLIER', 'EMPLOYEE'] : ['CUSTOMER', 'SUPPLIER']

  const canManage = (role: PartyRoleType) =>
    !archived && can(role === 'EMPLOYEE' ? PERMISSIONS.employeeManage : PERMISSIONS.partyManage)

  async function save(role: PartyRoleType, status: RoleStatus, current?: PartyRoleView, employeeNumber?: string) {
    setBusy(true)
    try {
      const updated = await api.put<PartyView>(`/parties/${party.id}/roles/${role}`, {
        status,
        since: current?.since ?? null,
        employeeNumber: employeeNumber ?? current?.employeeNumber ?? null,
      })
      onChange(updated)
      toast.success('Role updated.')
    } catch (error) {
      toast.error(problemMessage(error))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Card role="region" aria-label="Roles">
      <CardHeader>
        <CardTitle>Roles</CardTitle>
      </CardHeader>
      <CardContent className="space-y-3 text-sm">
        {roles.map((role) => {
          const current = party.roles.find((r) => r.role === role)
          const label = ROLE_LABELS[role]
          const active = current?.status === 'ACTIVE'
          return (
            <div key={role} className="space-y-2 border-b pb-3 last:border-b-0 last:pb-0">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <div>
                  <p className="font-medium">{label}</p>
                  <p className="text-xs text-muted-foreground">
                    {!current
                      ? 'Not set'
                      : active
                        ? current.since
                          ? `Active since ${formatDate(current.since)}`
                          : 'Active'
                        : 'Ended'}
                  </p>
                </div>
                {canManage(role) && (
                  <Button
                    size="sm"
                    variant="outline"
                    disabled={busy}
                    onClick={() => void save(role, active ? 'INACTIVE' : 'ACTIVE', current)}
                  >
                    {active
                      ? `End ${label.toLowerCase()} role`
                      : current
                        ? `Reactivate ${label.toLowerCase()} role`
                        : `Mark as ${label.toLowerCase()}`}
                  </Button>
                )}
              </div>
              {role === 'EMPLOYEE' && current && (
                <EmployeeNumber
                  current={current}
                  editable={canManage('EMPLOYEE')}
                  busy={busy}
                  onSave={(number) => void save('EMPLOYEE', current.status, current, number)}
                />
              )}
            </div>
          )
        })}
      </CardContent>
    </Card>
  )
}

function EmployeeNumber({
  current,
  editable,
  busy,
  onSave,
}: {
  current: PartyRoleView
  editable: boolean
  busy: boolean
  onSave: (number: string) => void
}) {
  const [value, setValue] = useState(current.employeeNumber ?? '')
  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    onSave(value.trim())
  }
  return (
    <form onSubmit={submit} className="flex items-end gap-2">
      <div className="space-y-1.5">
        <Label htmlFor="employee-number">Employee number</Label>
        <Input
          id="employee-number"
          value={value}
          maxLength={40}
          readOnly={!editable}
          onChange={(e) => setValue(e.target.value)}
        />
      </div>
      {editable && (
        <Button type="submit" size="sm" variant="outline" disabled={busy}>
          Save number
        </Button>
      )}
    </form>
  )
}
