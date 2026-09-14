import { useEffect, useMemo, useState } from 'react'
import { inlineParts, parseChangelog } from '../lib/changelog.js'

export const SEEN_KEY = 'kh.whatsnew.seen'

function readSeen() {
  try {
    return localStorage.getItem(SEEN_KEY)
  } catch {
    return null
  }
}

/**
 * Kosedeki surum/ortam etiketi ve "Yenilikler" penceresi. Pencere, bu tarayicida gorulen son surum
 * simdikinden farkliysa kendiliginden aciliyor (yeni surum cikinca bir kez).
 */
export default function WhatsNew({ text, version, environment }) {
  const releases = useMemo(() => parseChangelog(text), [text])
  const [open, setOpen] = useState(false)

  useEffect(() => {
    if (releases.length > 0 && readSeen() !== version) {
      setOpen(true)
    }
  }, [releases.length, version])

  useEffect(() => {
    if (!open) {
      return undefined
    }
    const onKey = (e) => e.key === 'Escape' && close()
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  })

  function close() {
    try {
      localStorage.setItem(SEEN_KEY, version)
    } catch {
      // gizli pencere vb.; bir sonraki ziyarette yine acilir
    }
    setOpen(false)
  }

  return (
    <>
      <button type="button" className={`version-badge env-${environment.toLowerCase()}`} onClick={() => setOpen(true)} title="Yenilikler">
        v{version} · {environment}
      </button>
      {open && (
        <div className="modal-backdrop" onClick={close}>
          <div className="modal" role="dialog" aria-modal="true" aria-labelledby="whatsnew-title" onClick={(e) => e.stopPropagation()}>
            <header className="modal-head">
              <h2 id="whatsnew-title">Yenilikler</h2>
              <button type="button" className="icon-btn" onClick={close} aria-label="Kapat">
                ×
              </button>
            </header>
            <div className="modal-body">
              {releases.map((r) => (
                <section key={r.version}>
                  <h3>
                    {r.version}
                    {r.date && <small> · {r.date}</small>}
                  </h3>
                  {r.groups.map((g, i) => (
                    <div key={i}>
                      {g.title && <h4>{g.title}</h4>}
                      <ul>
                        {g.items.map((item, j) => (
                          <li key={j}>
                            {inlineParts(item).map((p, k) => (p.code ? <code key={k}>{p.code}</code> : <span key={k}>{p.text}</span>))}
                          </li>
                        ))}
                      </ul>
                    </div>
                  ))}
                </section>
              ))}
            </div>
          </div>
        </div>
      )}
    </>
  )
}
