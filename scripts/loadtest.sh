#!/usr/bin/env bash
# Ani trafik senaryosunu kosar (loadtest/spike.js) ve bu sirada HPA'yi ornekler.
# Sonuclar /tmp/kesinti-loadtest/ altina yazilir; docs'a oradan aktariliyor.
#
# Kullanim (repo kokunden): bash scripts/loadtest.sh   ya da   make loadtest
set -uo pipefail
cd "$(dirname "$0")/.."
PATH="$HOME/.local/bin:$PATH"

CIKTI=${CIKTI:-/tmp/kesinti-loadtest}
NS=${NS:-kesinti-prod}
mkdir -p "$CIKTI"

if ! command -v k6 >/dev/null 2>&1; then
  echo "k6 kurulu degil. Kurulum: https://github.com/grafana/k6/releases (binary ~/.local/bin altina)"
  exit 1
fi

temizle() {
  [ -n "${ORNEK_PID:-}" ] && kill "$ORNEK_PID" 2>/dev/null
}
trap temizle EXIT

echo "--- baslangic durumu"
kubectl -n "$NS" get hpa api
kubectl -n "$NS" get pods -l app.kubernetes.io/name=api --no-headers | awk '{print "   ", $1, $2, $3}'

# HPA ve pod sayisini 10 saniyede bir ornekle.
(
  echo "zaman,replika,hedef_replika,cpu_yuzde,hazir_pod"
  while true; do
    satir=$(kubectl -n "$NS" get hpa api -o json 2>/dev/null | python3 -c "
import json, sys
try:
    d = json.load(sys.stdin)
except Exception:
    print(',,'); raise SystemExit
durum = d.get('status', {})
cpu = ''
for m in durum.get('currentMetrics') or []:
    if m.get('type') == 'Resource':
        cpu = (m.get('resource', {}).get('current', {}) or {}).get('averageUtilization', '')
print('%s,%s,%s' % (durum.get('currentReplicas', ''), durum.get('desiredReplicas', ''), cpu))
")
    hazir=$(kubectl -n "$NS" get pods -l app.kubernetes.io/name=api --no-headers 2>/dev/null | grep -c '1/1')
    echo "$(date -u +%H:%M:%S),$satir,$hazir"
    sleep 10
  done
) > "$CIKTI/hpa.csv" &
ORNEK_PID=$!

echo
echo "--- k6 basliyor (yaklasik 5 dakika)"
k6 run --summary-export "$CIKTI/k6-summary.json" loadtest/spike.js 2>&1 | tee "$CIKTI/k6.log" | tail -40

kill "$ORNEK_PID" 2>/dev/null
echo
echo "--- HPA ornekleri (replika degisimi)"
python3 - "$CIKTI/hpa.csv" <<'PY'
import csv, sys

with open(sys.argv[1], newline="") as f:
    satirlar = [s for s in csv.DictReader(f) if s.get("replika")]

if not satirlar:
    print("  ornek alinamadi")
    raise SystemExit

onceki = None
for s in satirlar:
    imza = (s["replika"], s["hedef_replika"])
    if imza != onceki:
        print(f"  {s['zaman']}  replika={s['replika']} hedef={s['hedef_replika']} cpu={s['cpu_yuzde'] or '-'}% hazir={s['hazir_pod']}")
        onceki = imza

enyuksek = max(int(s["replika"] or 0) for s in satirlar)
cpular = [int(s["cpu_yuzde"]) for s in satirlar if s["cpu_yuzde"]]
print(f"  en yuksek replika: {enyuksek}")
if cpular:
    print(f"  cpu: en yuksek {max(cpular)}%, ortalama {sum(cpular)//len(cpular)}%")
PY

echo
echo "--- sonuclar: $CIKTI (k6.log, k6-summary.json, hpa.csv)"
