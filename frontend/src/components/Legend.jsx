import { useState } from 'react'
import { LEVELS, PALETTES } from '../lib/colors.js'
import { TYPES } from '../lib/districts.js'

/**
 * Renk aciklamasi. unmapped: haritada poligonu bulunamayan ilceler ({il, ilce, total}), mesela
 * kaynagin bozuk yazdigi bir ilce adi. Sayilari kaybolmasin diye burada gosteriliyor.
 */
export default function Legend({ selected, unmapped }) {
  // Dar ekranda kapali basliyor, haritayi kapatmasin.
  const [open, setOpen] = useState(() => !globalThis.matchMedia?.('(max-width: 720px)')?.matches)
  const types = TYPES.filter((t) => !t.disabled && selected.has(t.id))
  const unmappedTotal = unmapped.reduce((sum, u) => sum + u.total, 0)

  return (
    <details className="legend" open={open} onToggle={(e) => setOpen(e.currentTarget.open)}>
      <summary>Aktif kesinti</summary>
      {types.length === 0 ? (
        <p className="muted">Tür seçilmedi.</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th />
              {LEVELS.map((l) => (
                <th key={l.min} scope="col">
                  {l.label}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {types.map((t) => (
              <Row key={t.id} label={t.label} palette={PALETTES[t.id]} />
            ))}
            {types.length > 1 && <Row label="Birden fazla tür" palette={PALETTES.MIXED} />}
          </tbody>
        </table>
      )}
      {unmappedTotal > 0 && (
        <p className="unmapped" title={unmapped.map((u) => `${u.il} / ${u.ilce}: ${u.total}`).join('\n')}>
          Haritada yeri bulunamayan: {unmappedTotal} kesinti
        </p>
      )}
    </details>
  )
}

function Row({ label, palette }) {
  return (
    <tr>
      <th scope="row">{label}</th>
      {palette.map((c) => (
        <td key={c}>
          <span className="swatch" style={{ background: c }} />
        </td>
      ))}
    </tr>
  )
}
