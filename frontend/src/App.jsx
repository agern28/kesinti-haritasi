import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import changelog from '../../CHANGELOG.md?raw'
import ConnectionBadge from './components/ConnectionBadge.jsx'
import DistrictPanel from './components/DistrictPanel.jsx'
import Freshness from './components/Freshness.jsx'
import Legend from './components/Legend.jsx'
import OutageMap from './components/OutageMap.jsx'
import TypeFilter from './components/TypeFilter.jsx'
import WhatsNew from './components/WhatsNew.jsx'
import { useNow } from './hooks/useNow.js'
import { fetchSources, fetchSummary } from './lib/api.js'
import { countsFor, districtId, groupSummary, nameKey } from './lib/districts.js'
import { connectLive } from './lib/live.js'

const version = import.meta.env.VITE_APP_VERSION ?? 'dev'
const environment = import.meta.env.VITE_APP_ENV ?? 'LOCAL'

// Ozet SSE ile guncelleniyor; bu aralik sadece bir olay kacarsa diye.
const SUMMARY_REFRESH_MS = 120_000
const SOURCES_REFRESH_MS = 60_000
// Ilk yuklemede ya da bir tarama bitince olaylar art arda geliyor; kisa bir sure toplanip tek seferde isleniyor.
const EVENT_BATCH_MS = 300

export default function App() {
  const [summary, setSummary] = useState(() => new Map())
  const [summaryError, setSummaryError] = useState(false)
  const [sources, setSources] = useState([])
  const [sourcesError, setSourcesError] = useState(false)
  const [selected, setSelected] = useState(() => new Set(['ELECTRICITY', 'WATER']))
  const [geo, setGeo] = useState(null)
  const [geoError, setGeoError] = useState(false)
  const [district, setDistrict] = useState(null)
  const [status, setStatus] = useState('connecting')
  const [flash, setFlash] = useState({ seq: 0, ids: [] })
  const [panelVersion, setPanelVersion] = useState(0)
  const now = useNow()

  const loadSummary = useCallback(
    () =>
      fetchSummary()
        .then((s) => {
          setSummary(groupSummary(s.districts))
          setSummaryError(false)
        })
        .catch(() => setSummaryError(true)),
    [],
  )
  const loadSources = useCallback(
    () =>
      fetchSources()
        .then((s) => {
          setSources(s)
          setSourcesError(false)
        })
        .catch(() => setSourcesError(true)),
    [],
  )

  useEffect(() => {
    loadSummary()
    loadSources()
    const a = setInterval(loadSummary, SUMMARY_REFRESH_MS)
    const b = setInterval(loadSources, SOURCES_REFRESH_MS)
    return () => {
      clearInterval(a)
      clearInterval(b)
    }
  }, [loadSummary, loadSources])

  const openDistrict = useRef(null)
  openDistrict.current = district?.id ?? null

  useEffect(() => {
    let timer = null
    let flashIds = new Set()
    let touched = new Set()
    let previous = null

    function flush() {
      loadSummary()
      if (flashIds.size > 0) {
        const ids = [...flashIds]
        setFlash((f) => ({ seq: f.seq + 1, ids }))
      }
      if (openDistrict.current && touched.has(openDistrict.current)) {
        setPanelVersion((v) => v + 1)
      }
      flashIds = new Set()
      touched = new Set()
    }

    const stop = connectLive({
      onStatus: (s) => {
        setStatus(s)
        // Kopup geri gelince kacirilan olaylar zaten geliyor; ozet ve kaynaklar yine de tazeleniyor.
        if (s === 'open' && previous === 'reconnecting') {
          loadSummary()
          loadSources()
          setPanelVersion((v) => v + 1)
        }
        previous = s
      },
      onEvent: (type, outage) => {
        if (type === 'outage.resync') {
          loadSummary()
          setPanelVersion((v) => v + 1)
          return
        }
        if (outage?.ilKey) {
          const id = districtId(outage.ilKey, outage.ilceKey)
          touched.add(id)
          // Haritada vurgulanan: aktif hale gelen kesinti (yeni ya da planli olup baslayan).
          if (outage.active && type !== 'outage.ended') {
            flashIds.add(id)
          }
        }
        clearTimeout(timer)
        timer = setTimeout(flush, EVENT_BATCH_MS)
      },
    })
    return () => {
      clearTimeout(timer)
      stop()
    }
  }, [loadSummary, loadSources])

  useEffect(() => {
    const onKey = (e) => e.key === 'Escape' && setDistrict(null)
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [])

  const handleLoaded = useCallback((props, err) => {
    if (err || !props) {
      setGeoError(true)
      return
    }
    setGeo(new Map(props.map((p) => [p.id, p])))
  }, [])

  const unmapped = useMemo(() => {
    if (!geo) {
      return []
    }
    return [...summary.values()]
      .filter((d) => !geo.has(d.id))
      .map((d) => ({ il: d.il, ilce: d.ilce, total: countsFor(d, selected).total }))
      .filter((d) => d.total > 0)
  }, [geo, summary, selected])

  // Panelde sorulacak adlar: ozetteki kaynak yazimlari + sinir verisindeki ad (+ il merkeziyse MERKEZ)
  const queries = useMemo(() => {
    if (!district) {
      return []
    }
    const list = [...(summary.get(district.id)?.names ?? []), { il: district.il, ilce: district.ilce }]
    if (nameKey(district.il) === nameKey(district.ilce)) {
      list.push({ il: district.il, ilce: 'MERKEZ' })
    }
    const seen = new Set()
    return list.filter((q) => {
      const k = `${nameKey(q.il)}|${nameKey(q.ilce)}`
      if (seen.has(k)) {
        return false
      }
      seen.add(k)
      return true
    })
  }, [district, summary])

  const sourceNames = useMemo(() => Object.fromEntries(sources.map((s) => [s.source, s.name])), [sources])

  return (
    <div className="app">
      <header className="topbar">
        <h1>Kesinti Haritası</h1>
        <TypeFilter selected={selected} onChange={setSelected} />
        <div className="topbar-right">
          <Freshness sources={sources} now={now} error={sourcesError} />
          <ConnectionBadge status={status} />
        </div>
      </header>
      <main className={`content${district ? ' with-panel' : ''}`}>
        <div className="map-wrap">
          <OutageMap
            summary={summary}
            selected={selected}
            selectedId={district?.id ?? null}
            flash={flash}
            onSelect={setDistrict}
            onLoaded={handleLoaded}
          />
          {(summaryError || geoError) && (
            <div className="map-error" role="alert">
              {geoError ? 'Harita sınırları yüklenemedi.' : 'Kesinti özeti alınamadı, tekrar denenecek.'}
            </div>
          )}
          <Legend selected={selected} unmapped={unmapped} />
          <WhatsNew text={changelog} version={version} environment={environment} />
        </div>
        {district && (
          <DistrictPanel
            district={district}
            queries={queries}
            selected={selected}
            sourceNames={sourceNames}
            refreshKey={panelVersion}
            onClose={() => setDistrict(null)}
          />
        )}
      </main>
    </div>
  )
}
