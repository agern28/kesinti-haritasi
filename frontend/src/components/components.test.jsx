import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ConnectionBadge from './ConnectionBadge.jsx'
import DistrictPanel from './DistrictPanel.jsx'
import Freshness from './Freshness.jsx'
import Legend from './Legend.jsx'
import TypeFilter from './TypeFilter.jsx'
import WhatsNew, { SEEN_KEY } from './WhatsNew.jsx'

const NOW = Date.parse('2026-09-14T10:00:00Z')

describe('ConnectionBadge', () => {
  it('durumu yaziyor', () => {
    const { rerender } = render(<ConnectionBadge status="open" />)
    expect(screen.getByRole('status')).toHaveTextContent('Canlı')
    rerender(<ConnectionBadge status="reconnecting" />)
    expect(screen.getByRole('status')).toHaveTextContent('Yeniden bağlanıyor')
  })
})

describe('Freshness', () => {
  const sources = [
    { source: 'BEDAS', name: 'BEDAŞ', type: 'ELECTRICITY', region: 'İstanbul Avrupa yakası', lastSuccessAt: '2026-09-14T09:58:00Z', stale: false },
    { source: 'IZSU', name: 'İZSU', type: 'WATER', region: 'İzmir', lastSuccessAt: '2026-09-14T08:00:00Z', stale: true },
  ]

  it('son guncellemeyi ve gecikmis kaynagi gosteriyor', () => {
    render(<Freshness sources={sources} now={NOW} />)
    expect(screen.getByRole('button')).toHaveTextContent('Son güncelleme: 2 dk önce')
    expect(screen.getByRole('button')).toHaveTextContent('1 kaynak gecikmeli')
  })

  it('tiklayinca kaynak bazinda son tarama', () => {
    render(<Freshness sources={sources} now={NOW} />)
    fireEvent.click(screen.getByRole('button'))
    const dialog = screen.getByRole('dialog', { name: 'Kaynaklar' })
    expect(dialog).toHaveTextContent('BEDAŞ')
    expect(dialog).toHaveTextContent('son tarama 2 dk önce')
    expect(dialog).toHaveTextContent('son tarama 2 sa önce · gecikmeli')
  })

  it('hic kaynak yoksa bilinmiyor', () => {
    render(<Freshness sources={[]} now={NOW} />)
    expect(screen.getByRole('button')).toHaveTextContent('Son güncelleme: bilinmiyor')
  })
})

describe('TypeFilter', () => {
  it('turu acip kapatiyor, dogalgaz secilemiyor', () => {
    const onChange = vi.fn()
    render(<TypeFilter selected={new Set(['ELECTRICITY', 'WATER'])} onChange={onChange} />)
    fireEvent.click(screen.getByLabelText('Su'))
    expect([...onChange.mock.calls[0][0]]).toEqual(['ELECTRICITY'])
    expect(screen.getByLabelText('Doğalgaz')).toBeDisabled()
  })
})

describe('Legend', () => {
  it('haritada yeri bulunamayan kesintileri sayiyor', () => {
    render(<Legend selected={new Set(['ELECTRICITY'])} unmapped={[{ il: 'TOKAT.', ilce: '.', total: 1 }]} />)
    expect(screen.getByText('Haritada yeri bulunamayan: 1 kesinti')).toBeInTheDocument()
  })
})

describe('DistrictPanel', () => {
  const outage = (over) => ({
    id: 'x',
    source: 'BEDAS',
    type: 'ELECTRICITY',
    planned: true,
    il: 'İSTANBUL',
    ilce: 'ESENLER',
    mahalleler: ['ORUÇREİS', 'TUNA'],
    startsAt: '2026-09-14T07:00:00Z',
    endsAt: '2026-09-14T11:00:00Z',
    reason: 'Bakım',
    sourceUrl: 'https://www.bedas.com.tr/kesinti',
    goneAt: null,
    active: true,
    ...over,
  })

  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(NOW)
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  function stubFetch(activeItems, otherItems) {
    const fetch = vi.fn((url) => {
      const items = url.includes('active=true') ? activeItems : otherItems
      return Promise.resolve({ ok: true, json: () => Promise.resolve({ items, page: 0, size: 100, total: items.length }) })
    })
    vi.stubGlobal('fetch', fetch)
    return fetch
  }

  const props = {
    district: { id: 'ISTANBUL|ESENLER', il: 'İstanbul', ilce: 'Esenler' },
    queries: [{ il: 'İSTANBUL', ilce: 'ESENLER' }],
    selected: new Set(['ELECTRICITY', 'WATER']),
    sourceNames: { BEDAS: 'BEDAŞ' },
    refreshKey: 0,
    onClose: () => {},
  }

  it('aktif ve yaklasan kesintileri, mahalleleri ve duyuru linkini listeliyor', async () => {
    const fetch = stubFetch(
      [outage({})],
      [
        outage({ id: 'future', active: false, startsAt: '2026-09-15T06:00:00Z', endsAt: '2026-09-15T10:00:00Z', mahalleler: ['HAVAALANI'] }),
        outage({ id: 'old', active: false, startsAt: '2026-09-10T06:00:00Z', endsAt: '2026-09-10T10:00:00Z' }),
      ],
    )
    render(<DistrictPanel {...props} />)
    expect(await screen.findByText('ORUÇREİS, TUNA')).toBeInTheDocument()
    expect(screen.getByText('HAVAALANI')).toBeInTheDocument()
    expect(screen.getAllByRole('link', { name: 'Duyuru' })[0]).toHaveAttribute('href', 'https://www.bedas.com.tr/kesinti')
    expect(screen.getAllByText('Kaynak: BEDAŞ', { exact: false })).toHaveLength(2)
    expect(screen.getByText('14 Eyl 10:00 - 14:00')).toBeInTheDocument()
    expect(fetch.mock.calls.map((c) => c[0])).toEqual([
      '/api/outages?il=%C4%B0STANBUL&ilce=ESENLER&active=true&size=200',
      '/api/outages?il=%C4%B0STANBUL&ilce=ESENLER&active=false&size=100',
    ])
  })

  it('kesinti yoksa bos mesaj, secili olmayan tur ayrica belirtiliyor', async () => {
    stubFetch([outage({ type: 'WATER' })], [])
    render(<DistrictPanel {...props} selected={new Set(['ELECTRICITY'])} />)
    expect(await screen.findByText('Aktif kesinti yok.')).toBeInTheDocument()
    expect(screen.getByText('Seçili olmayan türlerde 1 kesinti daha var.')).toBeInTheDocument()
  })

  it('refreshKey degisince yeniden yukluyor', async () => {
    const fetch = stubFetch([], [])
    const { rerender } = render(<DistrictPanel {...props} />)
    await screen.findByText('Aktif kesinti yok.')
    rerender(<DistrictPanel {...props} refreshKey={1} />)
    await waitFor(() => expect(fetch).toHaveBeenCalledTimes(4))
  })
})

describe('WhatsNew', () => {
  const text = '## [Yayınlanmamış]\n\n### Eklendi\n- Harita\n'

  beforeEach(() => localStorage.clear())

  it('yeni surumde kendiliginden aciliyor, kapatinca bir daha acilmiyor', () => {
    const { unmount } = render(<WhatsNew text={text} version="1.1.0" environment="PROD" />)
    expect(screen.getByRole('dialog', { name: 'Yenilikler' })).toHaveTextContent('Harita')
    fireEvent.click(screen.getByRole('button', { name: 'Kapat' }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(localStorage.getItem(SEEN_KEY)).toBe('1.1.0')
    unmount()

    render(<WhatsNew text={text} version="1.1.0" environment="PROD" />)
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('acilinca odak Kapat dugmesinde, Esc kapatiyor', () => {
    render(<WhatsNew text={text} version="1.1.0" environment="PROD" />)
    expect(screen.getByRole('button', { name: 'Kapat' })).toHaveFocus()
    fireEvent.keyDown(screen.getByRole('dialog'), { key: 'Escape' })
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('arka plana tiklayinca kapaniyor, pencerenin icine tiklayinca kapanmiyor', () => {
    const { container } = render(<WhatsNew text={text} version="1.1.0" environment="PROD" />)
    fireEvent.click(screen.getByRole('dialog'))
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    fireEvent.click(container.querySelector('.modal-backdrop'))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('surum/ortam etiketine tiklayinca aciliyor', () => {
    localStorage.setItem(SEEN_KEY, '1.1.0')
    render(<WhatsNew text={text} version="1.1.0" environment="INT" />)
    fireEvent.click(screen.getByRole('button', { name: 'v1.1.0 · INT' }))
    expect(screen.getByRole('dialog', { name: 'Yenilikler' })).toBeInTheDocument()
  })
})
