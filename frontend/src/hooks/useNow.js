import { useEffect, useState } from 'react'

/** "2 dk once" gibi goreli zamanlar kendiliginden ilerlesin diye belli araliklarla yeniden cizdirir. */
export function useNow(intervalMs = 30000) {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), intervalMs)
    return () => clearInterval(t)
  }, [intervalMs])
  return now
}
