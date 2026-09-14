// CHANGELOG.md'yi (Keep a Changelog bicimi) "Yenilikler" penceresi icin bolumlere ayirir.
// Markdown kutuphanesi yok: sadece "## [surum] - tarih", "### grup" ve "- madde" satirlari okunuyor.

const VERSION = /^##\s+\[([^\]]+)\](?:\s+-\s+(\S+))?/
const GROUP = /^###\s+(.+)/
const ITEM = /^\s*[-*]\s+(.+)/

export function parseChangelog(text) {
  const releases = []
  let release = null
  let group = null
  for (const line of (text ?? '').split(/\r?\n/)) {
    const v = VERSION.exec(line)
    if (v) {
      release = { version: v[1], date: v[2] ?? null, groups: [] }
      releases.push(release)
      group = null
      continue
    }
    if (!release) {
      continue
    }
    const g = GROUP.exec(line)
    if (g) {
      group = { title: g[1].trim(), items: [] }
      release.groups.push(group)
      continue
    }
    const i = ITEM.exec(line)
    if (i) {
      if (!group) {
        group = { title: null, items: [] }
        release.groups.push(group)
      }
      group.items.push(i[1].trim())
    }
  }
  return releases
}

/** Madde metnini duz metin ve `kod` parcalarina ayirir. */
export function inlineParts(text) {
  return text.split(/(`[^`]+`)/).filter(Boolean).map((p) =>
    p.startsWith('`') && p.endsWith('`') ? { code: p.slice(1, -1) } : { text: p },
  )
}
