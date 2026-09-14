const LABELS = {
  connecting: 'Bağlanıyor',
  open: 'Canlı',
  reconnecting: 'Yeniden bağlanıyor',
}

export default function ConnectionBadge({ status }) {
  return (
    <span className={`conn conn-${status}`} role="status" aria-live="polite" title="Canlı güncelleme bağlantısı">
      <span className="conn-dot" aria-hidden="true" />
      {LABELS[status] ?? status}
    </span>
  )
}
