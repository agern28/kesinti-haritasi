import { describe, expect, it } from 'vitest'
import { ago, timeRange } from './time.js'

const now = Date.parse('2026-09-14T10:00:00Z')

describe('ago', () => {
  it('goreli zaman', () => {
    expect(ago(null, now)).toBe('hiç')
    expect(ago('2026-09-14T09:59:30Z', now)).toBe('az önce')
    expect(ago('2026-09-14T09:58:00Z', now)).toBe('2 dk önce')
    expect(ago('2026-09-14T07:00:00Z', now)).toBe('3 sa önce')
    expect(ago('2026-09-12T09:00:00Z', now)).toBe('2 gün önce')
  })

  it('saat farki yuzunden gelecekte gorunen zaman "az once"', () => {
    expect(ago('2026-09-14T10:00:20Z', now)).toBe('az önce')
  })
})

describe('timeRange', () => {
  it('Turkiye saatiyle, ayni gun', () => {
    expect(timeRange('2026-09-14T07:00:00Z', '2026-09-14T11:00:00Z')).toBe('14 Eyl 10:00 - 14:00')
  })

  it('bitis ertesi gun', () => {
    expect(timeRange('2026-09-14T19:00:00Z', '2026-09-14T23:30:00Z')).toBe('14 Eyl 22:00 - 15 Eyl 02:30')
  })

  it('bitis yoksa soru isareti', () => {
    expect(timeRange('2026-09-14T07:00:00Z', null)).toBe('14 Eyl 10:00 - ?')
  })
})
