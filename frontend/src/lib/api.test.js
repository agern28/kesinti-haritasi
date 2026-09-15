import { afterEach, describe, expect, it, vi } from 'vitest'
import { fetchEnvironment, fetchOutages } from './api.js'

function stubFetch(impl) {
  const fetch = vi.fn(impl)
  vi.stubGlobal('fetch', fetch)
  return fetch
}

const json = (body) => Promise.resolve({ ok: true, json: () => Promise.resolve(body) })

describe('fetchEnvironment', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('nginx /env.json veriyorsa ortam', async () => {
    stubFetch(() => json({ environment: 'PROD' }))
    expect(await fetchEnvironment()).toBe('PROD')
  })

  it('bos, hatali ya da JSON olmayan cevapta null', async () => {
    stubFetch(() => json({ environment: '' }))
    expect(await fetchEnvironment()).toBeNull()
    stubFetch(() => Promise.resolve({ ok: false, status: 404 }))
    expect(await fetchEnvironment()).toBeNull()
    // npm run dev: Vite /env.json yerine index.html donuyor
    stubFetch(() => Promise.resolve({ ok: true, json: () => Promise.reject(new SyntaxError('HTML')) }))
    expect(await fetchEnvironment()).toBeNull()
  })
})

describe('fetchOutages', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('bos parametreleri gondermiyor, hatada HTTP kodunu veriyor', async () => {
    const fetch = stubFetch(() => json({ items: [] }))
    await fetchOutages({ il: 'İZMİR', ilce: undefined, active: true, size: null })
    expect(fetch.mock.calls[0][0]).toBe('/api/outages?il=%C4%B0ZM%C4%B0R&active=true')
    stubFetch(() => Promise.resolve({ ok: false, status: 503 }))
    await expect(fetchOutages({})).rejects.toThrow('HTTP 503')
  })
})
