import { describe, expect, it } from 'vitest'
import { fillFor, level, PALETTES } from './colors.js'

const both = new Set(['ELECTRICITY', 'WATER'])

describe('level', () => {
  it('sayiyi kademeye ceviriyor', () => {
    expect(level(0)).toBe(-1)
    expect(level(1)).toBe(0)
    expect(level(4)).toBe(1)
    expect(level(5)).toBe(2)
    expect(level(10)).toBe(3)
    expect(level(250)).toBe(3)
  })
})

describe('fillFor', () => {
  it('kesinti yoksa renk yok', () => {
    expect(fillFor(undefined, both)).toBeNull()
    expect(fillFor({ byType: { WATER: 3 } }, new Set(['ELECTRICITY']))).toBeNull()
  })

  it('tek turde o turun tonu', () => {
    expect(fillFor({ byType: { ELECTRICITY: 1 } }, both)).toBe(PALETTES.ELECTRICITY[0])
    expect(fillFor({ byType: { WATER: 12 } }, both)).toBe(PALETTES.WATER[3])
  })

  it('birden fazla turde karisik ton, toplam sayiya gore', () => {
    expect(fillFor({ byType: { ELECTRICITY: 2, WATER: 3 } }, both)).toBe(PALETTES.MIXED[2])
  })

  it('secili olmayan tur rengi etkilemiyor', () => {
    expect(fillFor({ byType: { ELECTRICITY: 2, WATER: 3 } }, new Set(['ELECTRICITY']))).toBe(PALETTES.ELECTRICITY[1])
  })
})
