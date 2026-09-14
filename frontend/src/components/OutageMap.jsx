import { useEffect, useRef } from 'react'
import L from 'leaflet'
import 'leaflet/dist/leaflet.css'
import { feature, mesh } from 'topojson-client'
import { fillFor } from '../lib/colors.js'
import { countsFor, TYPES } from '../lib/districts.js'

// Ilce sinirlari: HGK / OCHA HDX COD-AB (CC BY-IGO), sadelestirilmis. Uretimi: scripts/ilceler.py
export const GEO_URL = '/geo/ilceler.topo.json'

const TURKEY = [
  [35.8, 25.6],
  [42.2, 44.9],
]

function styleFor(id, { summary, selected, selectedId }) {
  const fill = fillFor(summary.get(id), selected)
  const isSelected = id === selectedId
  return {
    color: isSelected ? '#111827' : '#9aa5b1',
    weight: isSelected ? 3 : 0.5,
    fillColor: fill ?? '#ffffff',
    fillOpacity: fill ? 0.8 : 0.05,
  }
}

function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, (c) => `&#${c.charCodeAt(0)};`)
}

function tooltipHtml(props, { summary, selected }) {
  const { counts, total } = countsFor(summary.get(props.id), selected)
  const detail =
    total === 0
      ? 'Aktif kesinti yok'
      : TYPES.filter((t) => counts[t.id])
          .map((t) => `${t.label}: ${counts[t.id]}`)
          .join(' · ')
  return `<strong>${escapeHtml(props.ilce)}</strong>, ${escapeHtml(props.il)}<br>${detail}`
}

/**
 * Leaflet haritasi. Leaflet kendi DOM'unu yonettigi icin React disinda, ref'lerle suruluyor:
 * harita bir kez kuruluyor, ozet/filtre/secim degisince sadece stiller guncelleniyor.
 * flash: {seq, ids} - yeni aktif kesinti gelen ilceler kisa bir sure yanip soner.
 */
export default function OutageMap({ summary, selected, selectedId, flash, onSelect, onLoaded }) {
  const container = useRef(null)
  const mapRef = useRef(null)
  const layers = useRef(new Map())
  const latest = useRef(null)
  latest.current = { summary, selected, selectedId, onSelect, onLoaded }

  useEffect(() => {
    // Kesirli zoom (zoomSnap < 1) karolarin arasinda ince bosluklar birakiyor; tam sayi zoom kullaniliyor.
    // minZoom 5: dar ekranda Turkiye biraz tasiyor ama zoom 4'teki kadar kuculmuyor.
    const map = L.map(container.current, {
      minZoom: 5,
      maxBounds: L.latLngBounds(TURKEY).pad(0.3),
    })
    map.fitBounds(TURKEY)
    mapRef.current = map
    L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
      maxZoom: 18,
      attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> katkıcıları',
    }).addTo(map)
    map.attributionControl.addAttribution(
      'İlçe sınırları: <a href="https://data.humdata.org/dataset/cod-ab-tur">HGK / OCHA</a> (CC BY-IGO)',
    )
    map.attributionControl.setPrefix(false)

    // Panel acilip kapaninca harita alani degisiyor; Leaflet'e haber vermek gerekiyor.
    const resize = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(() => map.invalidateSize())
    resize?.observe(container.current)

    let cancelled = false
    fetch(GEO_URL)
      .then((res) => {
        if (!res.ok) {
          throw new Error(`${GEO_URL}: HTTP ${res.status}`)
        }
        return res.json()
      })
      .then((topo) => {
        if (cancelled) {
          return
        }
        const districts = feature(topo, topo.objects.ilceler)
        const districtLayer = L.geoJSON(districts, {
          style: (f) => styleFor(f.properties.id, latest.current),
          onEachFeature: (f, layer) => {
            layers.current.set(f.properties.id, layer)
            layer.bindTooltip(() => tooltipHtml(f.properties, latest.current), { sticky: true })
            layer.on({
              click: () => latest.current.onSelect(f.properties),
              mouseover: () => layer.setStyle({ weight: 2, color: '#1f2933' }),
              mouseout: () => layer.setStyle(styleFor(f.properties.id, latest.current)),
            })
          },
        }).addTo(map)
        // Poligonda ilce kimligi: tarayici testlerinde ve hata ayiklarken ilceyi bulmak icin.
        districtLayer.eachLayer((l) => l.getElement()?.setAttribute('data-id', l.feature.properties.id))
        // Il sinirlari ayri dosya degil: ilceler arasindaki, iki yaninda farkli il olan cizgiler.
        const borders = mesh(topo, topo.objects.ilceler, (a, b) => a === b || a.properties.il !== b.properties.il)
        L.geoJSON(borders, { style: { color: '#52606d', weight: 1.2, fill: false }, interactive: false }).addTo(map)
        latest.current.onLoaded?.(districts.features.map((f) => f.properties))
      })
      .catch((err) => latest.current.onLoaded?.(null, err))

    return () => {
      cancelled = true
      resize?.disconnect()
      layers.current.clear()
      mapRef.current = null
      map.remove()
    }
  }, [])

  useEffect(() => {
    const map = mapRef.current
    const layer = layers.current.get(selectedId)
    // Dar ekranda panel haritanin alt %60'ini kapatiyor; secilen ilce ustte kalan alanda gorunsun.
    if (map && layer && globalThis.matchMedia?.('(max-width: 720px)').matches) {
      map.fitBounds(layer.getBounds(), { paddingBottomRight: [0, map.getSize().y * 0.6], maxZoom: 9 })
    }
  }, [selectedId])

  useEffect(() => {
    for (const [id, layer] of layers.current) {
      layer.setStyle(styleFor(id, { summary, selected, selectedId }))
    }
  }, [summary, selected, selectedId])

  useEffect(() => {
    for (const id of flash?.ids ?? []) {
      const el = layers.current.get(id)?.getElement()
      if (!el) {
        continue
      }
      el.classList.remove('flash')
      // Animasyonu bastan baslatmak icin reflow
      void el.getBoundingClientRect()
      el.classList.add('flash')
      el.addEventListener('animationend', () => el.classList.remove('flash'), { once: true })
    }
  }, [flash])

  return <div ref={container} className="map" role="region" aria-label="Kesinti haritası" />
}
