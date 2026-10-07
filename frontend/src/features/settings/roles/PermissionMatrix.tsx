import { Checkbox } from '@/components/form/Checkbox'
import { MODULE_PHASES } from '@/features/shell/nav'
import type { PermissionView } from '@/lib/api/types'

/** Foundation permissions (module null) are grouped by the area their code starts with. */
const AREAS: Array<[prefix: string, label: string]> = [
  ['directory', 'Directory'],
  ['catalog', 'Products'],
  ['collaboration', 'Activities, tasks and documents'],
]

function groupKey(permission: PermissionView): string {
  if (permission.module) return permission.module
  const prefix = permission.code.split('.')[0]
  return AREAS.some(([area]) => area === prefix) ? `area:${prefix}` : ''
}

function rank(key: string): number {
  if (key === '') return 0
  const area = AREAS.findIndex(([prefix]) => `area:${prefix}` === key)
  return area >= 0 ? 1 + area : 1 + AREAS.length
}

/** Permissions grouped by area, then by module. */
export function PermissionMatrix({
  catalog,
  selected,
  disabled,
  onToggle,
}: {
  catalog: PermissionView[]
  selected: ReadonlySet<string>
  disabled: boolean
  onToggle: (code: string, on: boolean) => void
}) {
  const groups = new Map<string, PermissionView[]>()
  for (const permission of catalog) {
    const key = groupKey(permission)
    groups.set(key, [...(groups.get(key) ?? []), permission])
  }
  const keys = [...groups.keys()].sort((a, b) => rank(a) - rank(b) || a.localeCompare(b))

  return (
    <div className="space-y-4">
      {keys.map((key) => {
        const items = groups.get(key) ?? []
        const area = AREAS.find(([prefix]) => `area:${prefix}` === key)
        const label =
          key === ''
            ? 'Workspace administration'
            : area
              ? area[1]
              : `${MODULE_PHASES[key]?.label ?? key} module${items[0]?.moduleEnabled ? '' : ' (not enabled)'}`
        return (
          <fieldset key={key || 'foundation'} className="rounded-lg border p-4">
            <legend className="px-1 text-sm font-semibold">{label}</legend>
            <div className="grid gap-2 sm:grid-cols-2">
              {items.map((permission) => (
                <label key={permission.code} className="flex items-start gap-2 text-sm">
                  <Checkbox
                    className="mt-0.5"
                    checked={selected.has(permission.code)}
                    disabled={disabled}
                    onChange={(e) => onToggle(permission.code, e.target.checked)}
                  />
                  <span>
                    {permission.description}
                    <span className="block text-xs text-muted-foreground">{permission.code}</span>
                  </span>
                </label>
              ))}
            </div>
          </fieldset>
        )
      })}
    </div>
  )
}
