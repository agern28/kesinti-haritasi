import { describe, expect, it } from 'vitest'
import { countsFor, districtId, groupSummary, nameKey } from './districts.js'

describe('districtId', () => {
  it('bosluklari atiyor', () => {
    expect(districtId('ISTANBUL', 'GAZI OSMANPASA')).toBe('ISTANBUL|GAZIOSMANPASA')
    expect(districtId('ISTANBUL', 'GAZIOSMANPASA')).toBe('ISTANBUL|GAZIOSMANPASA')
  })

  it('MERKEZ ilin adina donuyor', () => {
    expect(districtId('BURDUR', 'MERKEZ')).toBe('BURDUR|BURDUR')
  })
})

describe('nameKey', () => {
  it('api ile ayni kural: Turkce buyuk harf ve isaretler', () => {
    expect(nameKey('Şişli')).toBe('SISLI')
    expect(nameKey('ŞİŞLİ')).toBe('SISLI')
    expect(nameKey('Kadıköy')).toBe('KADIKOY')
    expect(nameKey('Gazi Osmanpaşa')).toBe('GAZIOSMANPASA')
  })

  it('i harfinin arkasindaki ayri birlesik noktayi (U+0307) atiyor', () => {
    expect(nameKey('Şi̇şli̇')).toBe('SISLI')
  })
})

describe('groupSummary', () => {
  it('MERKEZ ve ilin adiyla gelen satirlari ayni ilcede topluyor', () => {
    const out = groupSummary([
      { il: 'BURDUR', ilce: 'MERKEZ', ilKey: 'BURDUR', ilceKey: 'MERKEZ', total: 2, planned: 2, unplanned: 0, byType: { ELECTRICITY: 2 } },
      { il: 'BURDUR', ilce: 'BURDUR', ilKey: 'BURDUR', ilceKey: 'BURDUR', total: 1, planned: 0, unplanned: 1, byType: { WATER: 1 } },
    ])
    expect(out.size).toBe(1)
    const d = out.get('BURDUR|BURDUR')
    expect(d.total).toBe(3)
    expect(d.byType).toEqual({ ELECTRICITY: 2, WATER: 1 })
    expect(d.names).toHaveLength(2)
  })
})

describe('countsFor', () => {
  it('sadece secili turleri sayiyor', () => {
    const s = { byType: { ELECTRICITY: 3, WATER: 2 } }
    expect(countsFor(s, new Set(['WATER']))).toEqual({ counts: { WATER: 2 }, total: 2 })
    expect(countsFor(undefined, new Set(['WATER']))).toEqual({ counts: {}, total: 0 })
  })
})
