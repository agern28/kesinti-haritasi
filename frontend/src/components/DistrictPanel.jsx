import { useEffect, useState } from 'react'
import { fetchOutages } from '../lib/api.js'
import OutageItem from './OutageItem.jsx'

const byStart = (a, b) => Date.parse(a.startsAt) - Date.parse(b.startsAt)

/**
 * Secili ilcenin kesintileri. queries: api'ye sorulacak il/ilce adlari. Bir poligon birden fazla
 * adla kayitli olabilir (il merkezi "MERKEZ" ya da ilin adiyla), hepsi soruluyor.
 * refreshKey degisince (SSE ile bu ilceye olay geldiginde) liste yeniden yukleniyor.
 */
export default function DistrictPanel({ district, queries, selected, sourceNames, refreshKey, onClose }) {
  const [state, setState] = useState({ loading: true, active: [], upcoming: [], error: null })
  const queryKey = JSON.stringify(queries)

  useEffect(() => {
    let cancelled = false
    setState((s) => ({ ...s, loading: true, error: null }))
    const now = Date.now()
    Promise.all(
      JSON.parse(queryKey).map((q) =>
        Promise.all([
          fetchOutages({ il: q.il, ilce: q.ilce, active: true, size: 200 }),
          // active=false: bitmis ya da henuz baslamamis. En yeni baslangic once geliyor, gelecektekiler basta.
          fetchOutages({ il: q.il, ilce: q.ilce, active: false, size: 100 }),
        ]),
      ),
    )
      .then((results) => {
        if (cancelled) {
          return
        }
        const active = new Map()
        const upcoming = new Map()
        for (const [a, b] of results) {
          a.items.forEach((o) => active.set(o.id, o))
          b.items
            .filter((o) => !o.goneAt && o.startsAt && Date.parse(o.startsAt) > now)
            .forEach((o) => upcoming.set(o.id, o))
        }
        setState({
          loading: false,
          error: null,
          active: [...active.values()].sort(byStart),
          upcoming: [...upcoming.values()].sort(byStart),
        })
      })
      .catch((e) => {
        if (!cancelled) {
          setState({ loading: false, active: [], upcoming: [], error: e.message })
        }
      })
    return () => {
      cancelled = true
    }
  }, [queryKey, refreshKey])

  const active = state.active.filter((o) => selected.has(o.type))
  const upcoming = state.upcoming.filter((o) => selected.has(o.type))
  const hidden = state.active.length - active.length + state.upcoming.length - upcoming.length
  const first = state.loading && state.active.length === 0 && state.upcoming.length === 0

  return (
    <aside className="panel" aria-label={`${district.ilce} kesintileri`}>
      <header className="panel-head">
        <div>
          <h2>{district.ilce}</h2>
          <p className="muted">{district.il}</p>
        </div>
        <button type="button" className="icon-btn" onClick={onClose} aria-label="Kapat">
          ×
        </button>
      </header>
      <div className="panel-body">
        {state.error && <p className="error">Kesintiler alınamadı: {state.error}</p>}
        {first ? (
          <p className="muted">Yükleniyor...</p>
        ) : (
          <>
            <Section title="Şu an süren" items={active} empty="Aktif kesinti yok." sourceNames={sourceNames} />
            <Section title="Yaklaşan" items={upcoming} empty="Yaklaşan kesinti yok." sourceNames={sourceNames} />
            {hidden > 0 && <p className="muted small">Seçili olmayan türlerde {hidden} kesinti daha var.</p>}
          </>
        )}
      </div>
    </aside>
  )
}

function Section({ title, items, empty, sourceNames }) {
  return (
    <section>
      <h3>
        {title} {items.length > 0 && <span className="count">{items.length}</span>}
      </h3>
      {items.length === 0 ? (
        <p className="muted">{empty}</p>
      ) : (
        <ul className="outages">
          {items.map((o) => (
            <OutageItem key={o.id} outage={o} sourceName={sourceNames[o.source]} />
          ))}
        </ul>
      )}
    </section>
  )
}
