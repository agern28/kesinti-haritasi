import { PALETTES } from '../lib/colors.js'
import { TYPES } from '../lib/districts.js'

export default function TypeFilter({ selected, onChange }) {
  function toggle(id) {
    const next = new Set(selected)
    if (next.has(id)) {
      next.delete(id)
    } else {
      next.add(id)
    }
    onChange(next)
  }

  return (
    <fieldset className="type-filter">
      <legend className="sr-only">Kesinti türü</legend>
      {TYPES.map((t) => (
        <label key={t.id} className={`chip${t.disabled ? ' disabled' : ''}`} title={t.disabled ? 'Henüz kaynak yok' : undefined}>
          <input
            type="checkbox"
            checked={!t.disabled && selected.has(t.id)}
            disabled={t.disabled}
            onChange={() => toggle(t.id)}
          />
          <span className="swatch" style={{ background: PALETTES[t.id][2] }} aria-hidden="true" />
          {t.label}
        </label>
      ))}
    </fieldset>
  )
}
