import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router'
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
import { Textarea } from '@/components/ui/textarea'
import { Field } from '@/components/form/Field'
import { FormError } from '@/components/form/FormError'
import { NativeSelect } from '@/components/form/NativeSelect'
import { useApi } from '@/lib/api/ApiContext'
import { ApiError } from '@/lib/api/client'
import { problemMessage } from '@/lib/api/problems'
import type { DuplicateCandidate, LeadView, PartyRef, StageView } from '@/lib/api/types'
import { duplicatesOf } from '@/features/records/duplicates'
import { invalidateCrmFigures } from './invalidation'
import { PartyPicker } from '@/features/records/PartyPicker'
import { moneySchema } from './schemas'

type Mode = 'create' | 'link' | 'none'

interface Section {
  mode: Mode
  existingId: string
  /** The candidate chosen from a duplicate notice, so the picker lists it before any search. */
  chosen: PartyRef | null
  candidates: DuplicateCandidate[] | null
  reason: string
}

const fresh = (mode: Mode): Section => ({
  mode,
  existingId: '',
  chosen: null,
  candidates: null,
  reason: '',
})

/**
 * D4: choose (link or create) an organization and a person, optionally open an opportunity. A probable duplicate
 * comes back as a 409 naming the section; the user links the candidate or gives a reason.
 */
export function ConvertLeadDialog({
  lead,
  onClose,
  onConverted,
}: {
  lead: LeadView
  onClose: () => void
  onConverted: (converted: LeadView) => void
}) {
  const api = useApi()
  const queryClient = useQueryClient()
  const stages = useQuery({
    queryKey: ['crm-stages'],
    queryFn: () => api.get<StageView[]>('/crm/pipeline/stages'),
  })
  const openStages = (stages.data ?? []).filter((s) => s.kind === 'OPEN')

  const [org, setOrg] = useState<Section>(fresh(lead.companyName ? 'create' : 'none'))
  const [orgName, setOrgName] = useState(lead.companyName ?? '')
  const [orgDomain, setOrgDomain] = useState('')
  const [person, setPerson] = useState<Section>(
    fresh(lead.firstName || lead.lastName ? 'create' : 'none'),
  )
  const [firstName, setFirstName] = useState(lead.firstName ?? '')
  const [lastName, setLastName] = useState(lead.lastName ?? '')
  const [withDeal, setWithDeal] = useState(true)
  const [dealName, setDealName] = useState(lead.companyName ?? lead.name)
  const [amount, setAmount] = useState(
    lead.estimatedValue == null ? '' : String(lead.estimatedValue),
  )
  const [stageId, setStageId] = useState('')
  const [closeOn, setCloseOn] = useState('')
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [formError, setFormError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  function section(mode: Mode, value: Section, fields: () => Record<string, unknown>) {
    if (mode === 'none') return null
    if (mode === 'link') return { existingId: value.existingId || null }
    return { ...fields(), duplicateReason: value.candidates ? value.reason.trim() || null : null }
  }

  /** Field keys that have a visible input right now; any other server message goes to the form error. */
  function visibleFields() {
    const keys = new Set<string>()
    if (org.mode === 'create') keys.add('organization.name').add('organization.domain')
    if (org.mode === 'link') keys.add('organization.existingId')
    if (person.mode === 'create') keys.add('person.firstName').add('person.lastName')
    if (person.mode === 'link') keys.add('person.existingId')
    if (withDeal) {
      keys
        .add('opportunity.name')
        .add('opportunity.amount')
        .add('opportunity.stageId')
        .add('opportunity.expectedCloseOn')
    }
    return keys
  }

  async function submit() {
    setErrors({})
    setFormError(null)
    const money = moneySchema.safeParse(amount)
    if (withDeal && !money.success) {
      setErrors({ 'opportunity.amount': money.error.issues[0].message })
      return
    }
    const amountText = withDeal && money.success ? money.data : ''
    setBusy(true)
    const body = {
      organization: section(org.mode, org, () => ({
        name: orgName.trim(),
        domain: orgDomain.trim() || null,
      })),
      person: section(person.mode, person, () => ({
        firstName: firstName.trim(),
        lastName: lastName.trim() || null,
        jobTitle: lead.jobTitle,
        email: lead.email,
        phone: lead.phone,
      })),
      opportunity: withDeal
        ? {
            name: dealName.trim(),
            amount: amountText === '' ? null : Number(amountText),
            currency: amountText === '' ? null : lead.currency,
            stageId: stageId || openStages[0]?.id || null,
            expectedCloseOn: closeOn || null,
          }
        : null,
      version: lead.version,
    }
    try {
      const converted = await api.post<LeadView>(`/leads/${lead.id}/convert`, body)
      queryClient.setQueryData(['lead', converted.id], converted)
      await queryClient.invalidateQueries({ queryKey: ['leads'] })
      await queryClient.invalidateQueries({ queryKey: ['crm-board'] })
      await queryClient.invalidateQueries({ queryKey: ['parties'] })
      await invalidateCrmFigures(queryClient)
      toast.success('Lead converted.')
      onConverted(converted)
    } catch (error) {
      const candidates = duplicatesOf(error)
      const party =
        error instanceof ApiError ? (error.problem as { party?: string }).party : undefined
      if (candidates && party === 'organization') setOrg((s) => ({ ...s, candidates }))
      else if (candidates && party === 'person') setPerson((s) => ({ ...s, candidates }))
      else if (error instanceof ApiError && error.problem.errors?.length) {
        const visible = visibleFields()
        const matched = error.problem.errors.filter((e) => visible.has(e.field))
        const unmatched = error.problem.errors.filter((e) => !visible.has(e.field))
        setErrors(Object.fromEntries(matched.map((e) => [e.field, e.message])))
        setFormError(unmatched.length ? unmatched.map((e) => e.message).join(' ') : null)
      } else setFormError(problemMessage(error))
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
          <DialogTitle>Convert {lead.name}</DialogTitle>
          <DialogDescription>
            Link the people and organizations you already have, or create them. The account becomes
            a customer.
          </DialogDescription>
        </DialogHeader>

        <fieldset className="space-y-3 rounded-lg border p-3">
          <legend className="px-1 text-sm font-medium">Organization</legend>
          <ModeRadios
            name="org"
            value={org.mode}
            onChange={(mode) => setOrg(fresh(mode))}
            noneLabel="No organization"
          />
          {org.mode === 'create' && (
            <div className="grid gap-3 sm:grid-cols-2">
              <TextInput
                id="convert-org-name"
                label="Organization name"
                value={orgName}
                error={errors['organization.name']}
                onChange={(v) => {
                  setOrgName(v)
                  setOrg((s) => ({ ...s, candidates: null, reason: '' }))
                }}
              />
              <TextInput
                id="convert-org-domain"
                label="Domain"
                value={orgDomain}
                error={errors['organization.domain']}
                onChange={(v) => {
                  setOrgDomain(v)
                  setOrg((s) => ({ ...s, candidates: null, reason: '' }))
                }}
              />
            </div>
          )}
          {org.mode === 'link' && (
            <PartyPicker
              id="convert-org-existing"
              label="Organization"
              kind="ORGANIZATION"
              value={org.existingId}
              current={org.chosen}
              error={errors['organization.existingId']}
              noneLabel="Choose…"
              onChange={(id) => setOrg((s) => ({ ...s, existingId: id }))}
            />
          )}
          {org.candidates && (
            <Duplicates
              id="convert-org-duplicate-reason"
              candidates={org.candidates}
              reason={org.reason}
              onClose={onClose}
              onReason={(reason) => setOrg((s) => ({ ...s, reason }))}
              onUse={(c) =>
                setOrg({ ...fresh('link'), existingId: c.id, chosen: { id: c.id, name: c.name } })
              }
            />
          )}
        </fieldset>

        <fieldset className="space-y-3 rounded-lg border p-3">
          <legend className="px-1 text-sm font-medium">Person</legend>
          <ModeRadios
            name="person"
            value={person.mode}
            onChange={(mode) => setPerson(fresh(mode))}
            noneLabel="No person"
          />
          {person.mode === 'create' && (
            <div className="grid gap-3 sm:grid-cols-2">
              <TextInput
                id="convert-first-name"
                label="First name"
                value={firstName}
                error={errors['person.firstName']}
                onChange={(v) => {
                  setFirstName(v)
                  setPerson((s) => ({ ...s, candidates: null, reason: '' }))
                }}
              />
              <TextInput
                id="convert-last-name"
                label="Last name"
                value={lastName}
                error={errors['person.lastName']}
                onChange={(v) => {
                  setLastName(v)
                  setPerson((s) => ({ ...s, candidates: null, reason: '' }))
                }}
              />
            </div>
          )}
          {person.mode === 'link' && (
            <PartyPicker
              id="convert-person-existing"
              label="Person"
              kind="PERSON"
              value={person.existingId}
              current={person.chosen}
              error={errors['person.existingId']}
              noneLabel="Choose…"
              onChange={(id) => setPerson((s) => ({ ...s, existingId: id }))}
            />
          )}
          {person.candidates && (
            <Duplicates
              id="convert-person-duplicate-reason"
              candidates={person.candidates}
              reason={person.reason}
              onClose={onClose}
              onReason={(reason) => setPerson((s) => ({ ...s, reason }))}
              onUse={(c) =>
                setPerson({
                  ...fresh('link'),
                  existingId: c.id,
                  chosen: { id: c.id, name: c.name },
                })
              }
            />
          )}
        </fieldset>

        <fieldset className="space-y-3 rounded-lg border p-3">
          <legend className="px-1 text-sm font-medium">Opportunity</legend>
          <label className="flex items-center gap-2 text-sm">
            <input
              type="checkbox"
              checked={withDeal}
              onChange={(e) => setWithDeal(e.target.checked)}
            />
            Create an opportunity
          </label>
          {withDeal && (
            <div className="grid gap-3 sm:grid-cols-2">
              <TextInput
                id="convert-deal-name"
                label="Opportunity name"
                value={dealName}
                error={errors['opportunity.name']}
                onChange={setDealName}
              />
              <TextInput
                id="convert-deal-amount"
                label={`Amount${lead.currency ? ` (${lead.currency})` : ''}`}
                value={amount}
                error={errors['opportunity.amount']}
                onChange={setAmount}
              />
              <Field id="convert-deal-stage" label="Stage" error={errors['opportunity.stageId']}>
                <NativeSelect
                  id="convert-deal-stage"
                  value={stageId || openStages[0]?.id || ''}
                  onChange={(e) => setStageId(e.target.value)}
                >
                  {openStages.map((s) => (
                    <option key={s.id} value={s.id}>
                      {s.name}
                    </option>
                  ))}
                </NativeSelect>
              </Field>
              <Field
                id="convert-deal-close"
                label="Expected close"
                error={errors['opportunity.expectedCloseOn']}
              >
                <Input
                  id="convert-deal-close"
                  type="date"
                  value={closeOn}
                  onChange={(e) => setCloseOn(e.target.value)}
                />
              </Field>
            </div>
          )}
        </fieldset>

        <FormError message={formError} />
        <DialogFooter>
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button onClick={() => void submit()} disabled={busy}>
            Convert lead
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

function ModeRadios({
  name,
  value,
  onChange,
  noneLabel,
}: {
  name: string
  value: Mode
  onChange: (m: Mode) => void
  noneLabel: string
}) {
  const options: Array<[Mode, string]> = [
    ['create', 'Create new'],
    ['link', 'Use existing'],
    ['none', noneLabel],
  ]
  return (
    <div role="radiogroup" className="flex flex-wrap gap-4 text-sm">
      {options.map(([mode, label]) => (
        <label key={mode} className="flex items-center gap-2">
          <input
            type="radio"
            name={`convert-${name}`}
            checked={value === mode}
            onChange={() => onChange(mode)}
          />
          {label}
        </label>
      ))}
    </div>
  )
}

function TextInput({
  id,
  label,
  value,
  onChange,
  error,
}: {
  id: string
  label: string
  value: string
  onChange: (v: string) => void
  error?: string
}) {
  return (
    <Field id={id} label={label} error={error}>
      <Input
        id={id}
        value={value}
        aria-invalid={error ? true : undefined}
        onChange={(e) => onChange(e.target.value)}
      />
    </Field>
  )
}

function Duplicates({
  id,
  candidates,
  reason,
  onReason,
  onUse,
  onClose,
}: {
  id: string
  candidates: DuplicateCandidate[]
  reason: string
  onReason: (v: string) => void
  onUse: (c: DuplicateCandidate) => void
  onClose: () => void
}) {
  return (
    <div
      role="alert"
      className="space-y-2 rounded-md border border-amber-300 bg-amber-50 p-3 text-sm dark:border-amber-800 dark:bg-amber-950/30"
    >
      <p className="font-medium">This looks like a record that already exists.</p>
      <ul className="space-y-1">
        {candidates.map((c) => (
          <li key={c.id} className="flex flex-wrap items-center gap-2">
            <Link to={`/app/directory/${c.id}`} className="underline" onClick={onClose}>
              {c.name}
            </Link>
            <Button size="sm" variant="outline" onClick={() => onUse(c)}>
              Use {c.name}
            </Button>
          </li>
        ))}
      </ul>
      <Label htmlFor={id}>Or keep a separate record because…</Label>
      <Textarea
        id={id}
        rows={2}
        maxLength={500}
        value={reason}
        onChange={(e) => onReason(e.target.value)}
      />
    </div>
  )
}
