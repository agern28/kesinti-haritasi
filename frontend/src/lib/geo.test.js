import { afterEach, describe, expect, it, vi } from 'vitest'
import { fetchDistricts, geoUrl, GEO_PATH } from './geo.js'

afterEach(() => vi.unstubAllGlobals())

describe('geoUrl', () => {
  it('adrese surum ekliyor', () => {
    expect(geoUrl('1.0.0')).toBe(`${GEO_PATH}?v=1.0.0`)
    expect(geoUrl('sha-1a2b3c/deneme')).toBe(`${GEO_PATH}?v=sha-1a2b3c%2Fdeneme`)
  })

  it('surum verilmezse build surumu ya da dev', () => {
    expect(geoUrl()).toMatch(/^\/geo\/ilceler\.topo\.json\?v=.+/)
  })
})

describe('fetchDistricts', () => {
  it('TopoJSON donuyor', async () => {
    const fetch = vi.fn(() => Promise.resolve({ ok: true, json: () => Promise.resolve({ type: 'Topology' }) }))
    vi.stubGlobal('fetch', fetch)
    await expect(fetchDistricts()).resolves.toEqual({ type: 'Topology' })
    expect(fetch.mock.calls[0][0]).toContain(`${GEO_PATH}?v=`)
  })

  it('200 disinda hata, adresi ve kodu soyluyor', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve({ ok: false, status: 404 })))
    await expect(fetchDistricts()).rejects.toThrow(/ilceler\.topo\.json.*HTTP 404/)
  })
})
