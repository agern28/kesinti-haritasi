// Canli olaylar (SSE). EventSource baglanti koparsa kendisi yeniden baglaniyor ve Last-Event-ID
// gonderiyor. Ama sunucu 200 disinda bir cevap donerse (api kapaliyken nginx 502 veriyor)
// baglantiyi kalici olarak kapatiyor. O durumda burada yeniden aciyoruz ve son olay kimligini
// ?lastEventId= ile veriyoruz; api kacirilan olaylari sirayla gonderiyor.

export const EVENTS = ['outage.created', 'outage.updated', 'outage.ended', 'outage.resync']

const CLOSED = 2

export function connectLive({
  url = '/api/stream',
  onEvent,
  onStatus,
  retryMs = 2000,
  maxRetryMs = 30000,
  EventSourceImpl = globalThis.EventSource,
}) {
  let source = null
  let lastId = null
  let timer = null
  let attempt = 0
  let stopped = false

  function open() {
    const target = lastId ? `${url}?lastEventId=${encodeURIComponent(lastId)}` : url
    source = new EventSourceImpl(target)
    source.onopen = () => {
      attempt = 0
      onStatus?.('open')
    }
    source.onerror = () => {
      if (stopped) {
        return
      }
      onStatus?.('reconnecting')
      if (source.readyState === CLOSED) {
        source.close()
        const delay = Math.min(retryMs * 2 ** attempt, maxRetryMs)
        attempt += 1
        timer = setTimeout(open, delay)
      }
    }
    for (const name of EVENTS) {
      source.addEventListener(name, (e) => {
        if (e.lastEventId) {
          lastId = e.lastEventId
        }
        let data = null
        try {
          data = JSON.parse(e.data)
        } catch {
          // outage.resync govdesi bos olabilir
        }
        onEvent?.(name, data, e.lastEventId)
      })
    }
  }

  onStatus?.('connecting')
  open()

  return () => {
    stopped = true
    clearTimeout(timer)
    source?.close()
  }
}
