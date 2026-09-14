import { useState } from 'react'
import { typeLabel } from '../lib/districts.js'
import { timeRange } from '../lib/time.js'

const SHOWN = 8

export default function OutageItem({ outage, sourceName }) {
  const [expanded, setExpanded] = useState(false)
  const mahalleler = outage.mahalleler ?? []
  const shown = expanded ? mahalleler : mahalleler.slice(0, SHOWN)

  return (
    <li className="outage">
      <div className="outage-head">
        <span className={`tag tag-${outage.type}`}>{typeLabel(outage.type)}</span>
        <span className="tag">{outage.planned ? 'Planlı' : 'Arıza'}</span>
        <span className="outage-time">{timeRange(outage.startsAt, outage.endsAt)}</span>
      </div>
      {mahalleler.length > 0 ? (
        <p className="mahalleler">
          {shown.join(', ')}
          {mahalleler.length > SHOWN && (
            <>
              {' '}
              <button type="button" className="link-btn" onClick={() => setExpanded((x) => !x)}>
                {expanded ? 'daha az' : `+${mahalleler.length - SHOWN} mahalle`}
              </button>
            </>
          )}
        </p>
      ) : (
        <p className="mahalleler muted">Mahalle bilgisi yok</p>
      )}
      {outage.reason && <p className="reason">{outage.reason}</p>}
      <p className="source">
        Kaynak: {sourceName ?? outage.source}
        {outage.sourceUrl && (
          <>
            {' · '}
            <a href={outage.sourceUrl} target="_blank" rel="noopener noreferrer">
              Duyuru
            </a>
          </>
        )}
      </p>
    </li>
  )
}
