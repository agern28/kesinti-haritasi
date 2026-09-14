// API istemcisi. Adresler goreli: compose'da ve k8s'te nginx /api'yi api servisine yonlendiriyor,
// gelistirmede Vite proxy'si (vite.config.js).

async function getJson(url) {
  const res = await fetch(url, { headers: { Accept: 'application/json' } })
  if (!res.ok) {
    throw new Error(`${url}: HTTP ${res.status}`)
  }
  return res.json()
}

export function fetchSummary() {
  return getJson('/api/map/summary')
}

export function fetchSources() {
  return getJson('/api/sources')
}

export function fetchOutages(params) {
  const q = new URLSearchParams()
  for (const [k, v] of Object.entries(params)) {
    if (v !== undefined && v !== null) {
      q.set(k, String(v))
    }
  }
  return getJson(`/api/outages?${q}`)
}
