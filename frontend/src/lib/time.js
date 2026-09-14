// Kesintiler Turkiye saatinde. Tarayici baska bir saat diliminde olsa da saatler Turkiye saatiyle gosteriliyor.
const TZ = 'Europe/Istanbul'

const dayFmt = new Intl.DateTimeFormat('tr-TR', { timeZone: TZ, day: 'numeric', month: 'short' })
const timeFmt = new Intl.DateTimeFormat('tr-TR', { timeZone: TZ, hour: '2-digit', minute: '2-digit' })

export function ago(iso, now = Date.now()) {
  if (!iso) {
    return 'hiç'
  }
  const sec = Math.max(0, Math.round((now - Date.parse(iso)) / 1000))
  if (sec < 60) {
    return 'az önce'
  }
  const min = Math.floor(sec / 60)
  if (min < 60) {
    return `${min} dk önce`
  }
  const hours = Math.floor(min / 60)
  if (hours < 24) {
    return `${hours} sa önce`
  }
  return `${Math.floor(hours / 24)} gün önce`
}

/** "14 Eyl 10:00 - 14:00", bitis baska gunse "14 Eyl 22:00 - 15 Eyl 02:00". */
export function timeRange(startsAt, endsAt) {
  if (!startsAt) {
    return ''
  }
  const s = new Date(startsAt)
  const start = `${dayFmt.format(s)} ${timeFmt.format(s)}`
  if (!endsAt) {
    return `${start} - ?`
  }
  const e = new Date(endsAt)
  const end = dayFmt.format(e) === dayFmt.format(s) ? timeFmt.format(e) : `${dayFmt.format(e)} ${timeFmt.format(e)}`
  return `${start} - ${end}`
}
