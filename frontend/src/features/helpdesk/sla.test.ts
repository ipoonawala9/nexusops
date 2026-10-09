import { describe, expect, it } from 'vitest'
import { anSla } from '@/test/records'
import { currentTarget, formatMinutes, formatRate } from './sla'

describe('formatMinutes', () => {
  it('uses the largest units that fit', () => {
    expect(formatMinutes(45)).toBe('45 min')
    expect(formatMinutes(60)).toBe('1 h')
    expect(formatMinutes(90)).toBe('1 h 30 min')
    expect(formatMinutes(1440)).toBe('1 d')
    expect(formatMinutes(1500)).toBe('1 d 1 h')
    expect(formatMinutes(7200)).toBe('5 d')
  })

  it('rounds fractions to whole minutes and never shows a negative', () => {
    expect(formatMinutes(315.4)).toBe('5 h 15 min')
    expect(formatMinutes(0.2)).toBe('0 min')
    expect(formatMinutes(-3)).toBe('0 min')
  })
})

describe('formatRate', () => {
  it('shows a percentage, or a dash when there is nothing to measure', () => {
    expect(formatRate(0.5)).toBe('50%')
    expect(formatRate(1)).toBe('100%')
    expect(formatRate(0.333)).toBe('33%')
    expect(formatRate(null)).toBe('—')
    expect(formatRate(undefined)).toBe('—')
  })
})

describe('currentTarget', () => {
  it('is the first response until someone has replied', () => {
    const sla = anSla({ firstResponseState: 'AT_RISK' })
    expect(currentTarget({ status: 'NEW', sla })).toEqual({
      target: 'First response',
      state: 'AT_RISK',
      due: sla.firstResponseDueAt,
    })
  })

  it('is the resolution once replied, and for resolved or closed tickets', () => {
    const replied = anSla({ firstRespondedAt: '2026-10-09T10:00:00Z', resolutionState: 'PAUSED' })
    expect(currentTarget({ status: 'PENDING', sla: replied })).toMatchObject({
      target: 'Resolution',
      state: 'PAUSED',
    })
    const resolved = anSla({ resolutionState: 'MET', resolvedAt: '2026-10-10T09:00:00Z' })
    expect(currentTarget({ status: 'RESOLVED', sla: resolved })).toMatchObject({
      target: 'Resolution',
      state: 'MET',
    })
  })

  it('keeps a late first response visible as breached while the ticket is open, as the breached filter does', () => {
    const late = anSla({
      firstRespondedAt: '2026-10-09T12:00:00Z',
      firstResponseState: 'BREACHED',
      resolutionState: 'ON_TRACK',
    })
    expect(currentTarget({ status: 'OPEN', sla: late })).toEqual({
      target: 'First response',
      state: 'BREACHED',
      due: late.firstResponseDueAt,
    })
    // a breached resolution is the one to show; a resolved ticket shows its resolution
    const both = anSla({ ...late, resolutionState: 'BREACHED' })
    expect(currentTarget({ status: 'OPEN', sla: both })).toMatchObject({
      target: 'Resolution',
      state: 'BREACHED',
    })
    const done = anSla({ ...late, resolutionState: 'MET', resolvedAt: '2026-10-10T09:00:00Z' })
    expect(currentTarget({ status: 'RESOLVED', sla: done })).toMatchObject({
      target: 'Resolution',
      state: 'MET',
    })
  })
})
