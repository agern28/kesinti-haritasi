import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { connectLive } from './live.js'

class FakeEventSource {
  static instances = []

  constructor(url) {
    this.url = url
    this.readyState = 0
    this.listeners = {}
    this.closed = false
    FakeEventSource.instances.push(this)
  }

  addEventListener(name, fn) {
    ;(this.listeners[name] ??= []).push(fn)
  }

  close() {
    this.closed = true
    this.readyState = 2
  }

  // test yardimcilari
  open() {
    this.readyState = 1
    this.onopen?.()
  }

  emit(name, data, id) {
    for (const fn of this.listeners[name] ?? []) {
      fn({ data, lastEventId: id ?? '' })
    }
  }

  fail(readyState) {
    this.readyState = readyState
    this.onerror?.()
  }
}

describe('connectLive', () => {
  beforeEach(() => {
    FakeEventSource.instances = []
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('olaylari JSON olarak iletiyor, durumu bildiriyor', () => {
    const events = []
    const statuses = []
    connectLive({ EventSourceImpl: FakeEventSource, onEvent: (...a) => events.push(a), onStatus: (s) => statuses.push(s) })
    const es = FakeEventSource.instances[0]
    es.open()
    es.emit('outage.created', '{"id":"a","active":true}', '1-0')
    es.emit('outage.resync', '{}')
    expect(statuses).toEqual(['connecting', 'open'])
    expect(events).toEqual([
      ['outage.created', { id: 'a', active: true }, '1-0'],
      ['outage.resync', {}, ''],
    ])
  })

  it('tarayici kendisi yeniden baglaniyorsa (CONNECTING) yeni baglanti acmiyor', () => {
    const statuses = []
    connectLive({ EventSourceImpl: FakeEventSource, onStatus: (s) => statuses.push(s) })
    FakeEventSource.instances[0].open()
    FakeEventSource.instances[0].fail(0)
    vi.advanceTimersByTime(60000)
    expect(FakeEventSource.instances).toHaveLength(1)
    expect(statuses.at(-1)).toBe('reconnecting')
  })

  it('baglanti kalici kapaninca son olay kimligiyle yeniden aciyor, bekleme artiyor', () => {
    connectLive({ EventSourceImpl: FakeEventSource, retryMs: 1000 })
    const first = FakeEventSource.instances[0]
    first.open()
    first.emit('outage.updated', '{}', '1789-0')
    first.fail(2)
    expect(first.closed).toBe(true)
    vi.advanceTimersByTime(999)
    expect(FakeEventSource.instances).toHaveLength(1)
    vi.advanceTimersByTime(1)
    const second = FakeEventSource.instances[1]
    expect(second.url).toBe('/api/stream?lastEventId=1789-0')

    second.fail(2)
    vi.advanceTimersByTime(1999)
    expect(FakeEventSource.instances).toHaveLength(2)
    vi.advanceTimersByTime(1)
    expect(FakeEventSource.instances).toHaveLength(3)
  })

  it('durdurulunca kapaniyor ve yeniden denemiyor', () => {
    const stop = connectLive({ EventSourceImpl: FakeEventSource })
    const es = FakeEventSource.instances[0]
    stop()
    es.fail(2)
    vi.advanceTimersByTime(60000)
    expect(es.closed).toBe(true)
    expect(FakeEventSource.instances).toHaveLength(1)
  })
})
