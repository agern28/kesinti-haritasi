import { useState } from 'react'
import { typeLabel } from '../lib/districts.js'
import { ago } from '../lib/time.js'

/** Kaynaklarin en son basarili taramasi: "Son guncelleme" bu. */
export function latestSuccess(sources) {
  let best = null
  for (const s of sources ?? []) {
    if (s.lastSuccessAt && (!best || Date.parse(s.lastSuccessAt) > Date.parse(best))) {
      best = s.lastSuccessAt
    }
  }
  return best
}

export default function Freshness({ sources, now, error }) {
  const [open, setOpen] = useState(false)
  const stale = (sources ?? []).filter((s) => s.stale)
  const last = latestSuccess(sources)

  return (
    <div className="freshness">
      <button
        type="button"
        className={`freshness-btn${stale.length > 0 ? ' has-stale' : ''}`}
        aria-expanded={open}
        onClick={() => setOpen((o) => !o)}
      >
        <span>Son güncelleme: {last ? ago(last, now) : 'bilinmiyor'}</span>
        {stale.length > 0 && <span className="stale-count">{stale.length} kaynak gecikmeli</span>}
        {error && <span className="stale-count">kaynak durumu alınamadı</span>}
      </button>
      {open && (
        <div className="sources-pop" role="dialog" aria-label="Kaynaklar">
          <ul>
            {(sources ?? []).map((s) => (
              <li key={s.source} className={s.stale ? 'stale' : undefined}>
                <div className="src-name">
                  {s.name}
                  <small>
                    {' '}
                    {[typeLabel(s.type), s.region].filter(Boolean).join(' · ')}
                  </small>
                </div>
                <div className="src-time">
                  {s.lastSuccessAt ? `son tarama ${ago(s.lastSuccessAt, now)}` : 'henüz taranmadı'}
                  {s.stale && <strong> · gecikmeli</strong>}
                </div>
              </li>
            ))}
          </ul>
          <p className="muted small">
            İSKİ verisi geçmiş veri, günde bir taranıyor. Haritada aktif kesinti olarak görünmüyor.
          </p>
        </div>
      )}
    </div>
  )
}
