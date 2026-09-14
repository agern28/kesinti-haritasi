// Kaynaktaki il/ilce ile harita sinirlarini eslestiren kimlik: "IL|ILCE".
// API ilKey/ilceKey'i hazir veriyor (Turkce karakter ve buyuk/kucuk harften bagimsiz).
// Iki fark kaliyor:
// - Kaynaklar il merkezini "MERKEZ" diye yaziyor, sinir verisinde merkez ilce ilin adini tasiyor.
// - Bosluklar: sinir verisinde "Gazi Osmanpasa", kaynaklarda "GAZIOSMANPASA". Bosluklar atiliyor.
// Sinir dosyasindaki kimlikler ayni kuralla uretiliyor (scripts/ilceler.py).
export function districtId(ilKey, ilceKey) {
  const il = compact(ilKey)
  const ilce = compact(ilceKey)
  return `${il}|${ilce === 'MERKEZ' ? il : ilce}`
}

function compact(key) {
  return (key ?? '').replace(/\s+/g, '')
}

/**
 * api'deki Names.key kurali, bosluksuz: "Şişli" -> "SISLI", "Gazi Osmanpaşa" -> "GAZIOSMANPASA".
 * Once Turkce buyuk harf (i -> İ, ı -> I), sonra NFD ile isaretler atiliyor.
 */
export function nameKey(raw) {
  return (raw ?? '')
    .toLocaleUpperCase('tr-TR')
    .normalize('NFD')
    .replace(/\p{M}+/gu, '')
    .replace(/[^\p{L}\p{N}]+/gu, '')
}

export const TYPES = [
  { id: 'ELECTRICITY', label: 'Elektrik' },
  { id: 'WATER', label: 'Su' },
  // Dogalgaz kaynagi henuz yok (Faz 9). Filtrede gorunuyor ama secilemiyor.
  { id: 'GAS', label: 'Doğalgaz', disabled: true },
]

export function typeLabel(type) {
  return TYPES.find((t) => t.id === type)?.label ?? type
}

/**
 * /api/map/summary satirlarini harita kimligine gore toplar. Ayni ilce iki adla gelebilir
 * ("MERKEZ" ve "BURDUR"), ikisi ayni poligona duser.
 */
export function groupSummary(districts) {
  const out = new Map()
  for (const d of districts ?? []) {
    const id = districtId(d.ilKey, d.ilceKey)
    const cur = out.get(id) ?? { id, il: d.il, ilce: d.ilce, names: [], total: 0, planned: 0, unplanned: 0, byType: {} }
    cur.names.push({ il: d.il, ilce: d.ilce })
    cur.total += d.total
    cur.planned += d.planned
    cur.unplanned += d.unplanned
    for (const [type, n] of Object.entries(d.byType ?? {})) {
      cur.byType[type] = (cur.byType[type] ?? 0) + n
    }
    out.set(id, cur)
  }
  return out
}

/** Secili turlerdeki aktif kesinti sayisi, tur bazinda. */
export function countsFor(summary, selected) {
  const counts = {}
  let total = 0
  for (const type of selected) {
    const n = summary?.byType?.[type] ?? 0
    if (n > 0) {
      counts[type] = n
      total += n
    }
  }
  return { counts, total }
}
