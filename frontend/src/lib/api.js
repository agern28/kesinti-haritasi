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

/**
 * Ortam etiketi (LOCAL/INT/PROD) calisma aninda nginx'ten (/env.json, container'in APP_ENV'i).
 * Ayni imaj INT'te ve PROD'da calistigi icin build sirasinda gomulemiyor.
 * Yoksa (npm run dev'de Vite index.html donuyor) null.
 */
export async function fetchEnvironment() {
  try {
    const env = (await getJson('/env.json')).environment
    return typeof env === 'string' && env.trim() ? env.trim() : null
  } catch {
    return null
  }
}
