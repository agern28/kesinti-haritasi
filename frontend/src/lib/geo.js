// Ilce sinirlari: HGK / OCHA HDX COD-AB (CC BY-IGO), sadelestirilmis. Uretimi: scripts/ilceler.py
// Dosya nginx'te 7 gun cache'li ve adi sabit; adrese surum ekleniyor ki yeni surum eski sinirlari kullanmasin.
export const GEO_PATH = '/geo/ilceler.topo.json'

export function geoUrl(version = import.meta.env.VITE_APP_VERSION ?? 'dev') {
  return `${GEO_PATH}?v=${encodeURIComponent(version)}`
}

/** Sinir dosyasini indirir (TopoJSON). 200 disinda bir cevapta hata firlatir. */
export async function fetchDistricts() {
  const url = geoUrl()
  const res = await fetch(url)
  if (!res.ok) {
    throw new Error(`${url}: HTTP ${res.status}`)
  }
  return res.json()
}
