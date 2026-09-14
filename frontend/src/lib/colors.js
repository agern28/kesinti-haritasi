import { countsFor } from './districts.js'

// Acikten koyuya dort ton. Ton turu, koyuluk sayiyi gosteriyor.
export const PALETTES = {
  ELECTRICITY: ['#fde68a', '#fbbf24', '#f59e0b', '#b45309'],
  WATER: ['#bae6fd', '#38bdf8', '#0284c7', '#075985'],
  GAS: ['#fecaca', '#f87171', '#dc2626', '#991b1b'],
  MIXED: ['#e9d5ff', '#c084fc', '#9333ea', '#6b21a8'],
}

export const LEVELS = [
  { min: 1, label: '1' },
  { min: 2, label: '2-4' },
  { min: 5, label: '5-9' },
  { min: 10, label: '10+' },
]

export function level(n) {
  let i = -1
  LEVELS.forEach((l, idx) => {
    if (n >= l.min) i = idx
  })
  return i
}

/**
 * Bir ilcenin dolgu rengi. Secili turlerde aktif kesinti yoksa null.
 * Tek tur varsa o turun tonu, birden fazla tur varsa "karisik" tonu.
 */
export function fillFor(summary, selected) {
  const { counts, total } = countsFor(summary, selected)
  if (total === 0) {
    return null
  }
  const types = Object.keys(counts)
  const palette = types.length === 1 ? PALETTES[types[0]] : PALETTES.MIXED
  return palette[level(total)]
}
