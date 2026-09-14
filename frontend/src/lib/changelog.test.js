import { describe, expect, it } from 'vitest'
import { inlineParts, parseChangelog } from './changelog.js'

const SAMPLE = `# Değişiklikler

Açıklama paragrafı, okunmuyor.

## [Yayınlanmamış]

### Eklendi
- Harita
- \`/api/stream\` ile canlı güncelleme

## [1.0.0] - 2026-10-01

### Düzeltildi
- Bir hata
`

describe('parseChangelog', () => {
  it('surumleri, gruplari ve maddeleri ayiriyor', () => {
    const out = parseChangelog(SAMPLE)
    expect(out).toEqual([
      { version: 'Yayınlanmamış', date: null, groups: [{ title: 'Eklendi', items: ['Harita', '`/api/stream` ile canlı güncelleme'] }] },
      { version: '1.0.0', date: '2026-10-01', groups: [{ title: 'Düzeltildi', items: ['Bir hata'] }] },
    ])
  })

  it('bos metin', () => {
    expect(parseChangelog('')).toEqual([])
    expect(parseChangelog(undefined)).toEqual([])
  })
})

describe('inlineParts', () => {
  it('kod parcalarini ayiriyor', () => {
    expect(inlineParts('`/api/stream` ile canlı')).toEqual([{ code: '/api/stream' }, { text: ' ile canlı' }])
  })
})
