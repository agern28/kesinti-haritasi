#!/usr/bin/env bash
# Lokal kumedeki kurulumu bastan sona dener: Argo CD, pod'lar, sertifikalar, iki ortamin
# adresleri, SSE, veritabanlari, collector ayarlari, HPA.
#
# scripts/local-check.sh compose yigini icin, bu da kume icin.
# Kullanim (repo kokunden): bash scripts/cluster-check.sh   ya da   make cluster-check
set -uo pipefail
cd "$(dirname "$0")/.."

PATH="$HOME/.local/bin:$PATH"
INT_HOST=${INT_HOST:-int.kesinti.localhost}
PROD_HOST=${PROD_HOST:-kesinti.localhost}
ARGOCD_HOST=${ARGOCD_HOST:-argocd.localhost}
hatali=0

ok()   { echo "ok    $1"; }
fail() { echo "HATA  $1"; hatali=$((hatali + 1)); }
bekle() { if [ "$2" = "$3" ]; then ok "$1 ($3)"; else fail "$1: beklenen '$2', gelen '$3'"; fi; }

# Ingress'e localhost'tan gider; *.localhost adlarini WSL cozmedigi icin --resolve kullaniliyor.
istek() { # host, yol
  curl -sk --resolve "$1:443:127.0.0.1" -o /dev/null -w '%{http_code}' --max-time 30 "https://$1$2"
}
govde() { curl -sk --resolve "$1:443:127.0.0.1" --max-time 30 "https://$1$2"; }

echo "--- kume"
dugum=$(kubectl get nodes --no-headers 2>/dev/null | grep -c " Ready ")
bekle "dugumler Ready" "2" "$dugum"

echo "--- Argo CD"
# Beklenen sayi repodan: gitops/apps altindaki her dosya bir Application, bir de kok Application.
beklenen_app=$(( $(ls gitops/apps/*.yaml 2>/dev/null | wc -l) + 1 ))
toplam=$(kubectl -n argocd get applications --no-headers 2>/dev/null | wc -l | tr -d ' ')
bekle "application sayisi" "$beklenen_app" "$toplam"
bozuk=$(kubectl -n argocd get applications -o jsonpath='{range .items[*]}{.metadata.name}={.status.sync.status}/{.status.health.status} {end}' 2>/dev/null | tr ' ' '\n' | grep -v 'Synced/Healthy' | grep -v '^$')
if [ -z "$bozuk" ]; then ok "hepsi Synced/Healthy"; else fail "Synced/Healthy olmayanlar: $bozuk"; fi
bekle "Argo CD arayuzu" "200" "$(istek "$ARGOCD_HOST" /)"

echo "--- pod'lar"
for ns in kesinti-data kesinti-int kesinti-prod; do
  calisan=$(kubectl -n "$ns" get pods --no-headers 2>/dev/null | grep -c "Running")
  toplam_pod=$(kubectl -n "$ns" get pods --no-headers 2>/dev/null | wc -l | tr -d ' ')
  if [ "$toplam_pod" -gt 0 ] && [ "$calisan" = "$toplam_pod" ]; then
    ok "$ns: $calisan/$toplam_pod Running"
  else
    fail "$ns: $calisan/$toplam_pod Running"
  fi
done

echo "--- sertifikalar (kendi CA'mizdan)"
for ns_ad in "kesinti-int frontend-tls" "kesinti-prod frontend-tls" "argocd argocd-tls"; do
  set -- $ns_ad
  durum=$(kubectl -n "$1" get certificate "$2" -o jsonpath='{.status.conditions[0].status}' 2>/dev/null)
  bekle "$1/$2 hazir" "True" "${durum:-yok}"
done
imzalayan=$(echo | openssl s_client -connect 127.0.0.1:443 -servername "$PROD_HOST" 2>/dev/null | openssl x509 -noout -issuer 2>/dev/null)
case "$imzalayan" in
  *"Kesinti Haritasi Lokal CA"*) ok "sertifikayi kendi CA'miz imzalamis" ;;
  *) fail "beklenmeyen imzalayan: $imzalayan" ;;
esac

echo "--- INT ($INT_HOST)"
bekle "ana sayfa" "200" "$(istek "$INT_HOST" /)"
bekle "env.json" '{"environment":"INT"}' "$(govde "$INT_HOST" /env.json)"
bekle "harita ozeti" "200" "$(istek "$INT_HOST" /api/map/summary)"
bekle "derin sayfa reddediliyor" "400" "$(istek "$INT_HOST" "/api/outages?page=2000&size=100")"

echo "--- PROD ($PROD_HOST)"
bekle "ana sayfa" "200" "$(istek "$PROD_HOST" /)"
bekle "env.json" '{"environment":"PROD"}' "$(govde "$PROD_HOST" /env.json)"
bekle "harita ozeti" "200" "$(istek "$PROD_HOST" /api/map/summary)"
bekle "kaynak durumu" "200" "$(istek "$PROD_HOST" /api/sources)"
geo=$(curl -sk --resolve "$PROD_HOST:443:127.0.0.1" -o /dev/null -w '%{size_download}' --max-time 60 "https://$PROD_HOST/geo/ilceler.topo.json?v=1.0.0")
if [ "${geo:-0}" -gt 300000 ]; then ok "ilce sinirlari ($geo bayt)"; else fail "ilce sinirlari kucuk geldi: ${geo:-0}"; fi

echo "--- canli akis (SSE, Ingress uzerinden)"
akis=$(timeout 25 curl -sk -N --resolve "$PROD_HOST:443:127.0.0.1" -H 'Accept: text/event-stream' "https://$PROD_HOST/api/stream" 2>/dev/null | head -c 60)
case "$akis" in
  *bagli*) ok "stream baglandi" ;;
  *) fail "stream'den 25 saniyede beklenen cevap gelmedi: '$akis'" ;;
esac

echo "--- veritabanlari"
# -d postgres sart: kullanici adiyla ayni isimde bir veritabani yok (POSTGRES_DB=postgres).
listede=$(kubectl -n kesinti-data exec postgres-0 -- psql -U kesinti -d postgres -tAc \
  "select count(*) from pg_database where datname in ('kesinti_int','kesinti_prod');" 2>/dev/null | tr -d '\r')
bekle "iki ortam veritabani" "2" "${listede:-0}"
prod_satir=$(kubectl -n kesinti-data exec postgres-0 -- psql -U kesinti -d kesinti_prod -tAc 'select count(*) from outage;' 2>/dev/null | tr -d '\r')
if [ "${prod_satir:-0}" -gt 0 ]; then ok "PROD veritabaninda $prod_satir kayit"; else fail "PROD veritabani bos"; fi

echo "--- collector ayarlari (ayni anda tek tarama)"
int_sched=$(kubectl -n kesinti-int get deploy collector -o jsonpath='{.spec.template.spec.containers[0].env[?(@.name=="COLLECTOR_SCHEDULING_ENABLED")].value}' 2>/dev/null)
prod_sched=$(kubectl -n kesinti-prod get deploy collector -o jsonpath='{.spec.template.spec.containers[0].env[?(@.name=="COLLECTOR_SCHEDULING_ENABLED")].value}' 2>/dev/null)
bekle "INT tarama kapali" "false" "$int_sched"
bekle "PROD tarama acik" "true" "$prod_sched"
compose_collector=$(docker compose ps --status running --format '{{.Service}}' 2>/dev/null | grep -c '^collector$')
if [ "$compose_collector" = "0" ]; then
  ok "compose collector kapali"
else
  fail "compose collector de calisiyor: ayni kaynaga iki yerden gidiliyor (docker compose stop collector)"
fi

echo "--- izleme"
izleme_pod=$(kubectl -n monitoring get pods --no-headers 2>/dev/null | grep -cE "Running")
izleme_toplam=$(kubectl -n monitoring get pods --no-headers 2>/dev/null | wc -l | tr -d ' ')
if [ "${izleme_toplam:-0}" -gt 0 ] && [ "$izleme_pod" = "$izleme_toplam" ]; then
  ok "monitoring: $izleme_pod/$izleme_toplam Running"
else
  fail "monitoring: $izleme_pod/${izleme_toplam:-0} Running"
fi
bekle "Grafana" "200" "$(istek "${GRAFANA_HOST:-grafana.localhost}" /login)"
kural=$(kubectl -n monitoring get prometheusrule kesinti-haritasi -o jsonpath='{.metadata.name}' 2>/dev/null)
bekle "alarm kurallari kurulu" "kesinti-haritasi" "${kural:-yok}"
pano=$(kubectl -n monitoring get configmap -l grafana_dashboard=1 --no-headers 2>/dev/null | grep -c '^pano-')
bekle "kendi panolarimiz" "3" "${pano:-0}"
# Hedefler: kendi iki servisimiz iki ortamda, yani dort tane up olmali.
# Prometheus container'inda curl/wget yok, API'ye port-forward ile bakiliyor.
if command -v python3 >/dev/null 2>&1; then
  kubectl -n monitoring port-forward svc/monitoring-kube-prometheus-prometheus 9090:9090 >/dev/null 2>&1 &
  pf=$!
  sleep 5
  up=$(curl -s --max-time 15 'http://localhost:9090/api/v1/query?query=count(up%7Bjob%3D~%22collector%7Capi%22%2Cnamespace%3D~%22kesinti-.%2A%22%7D%20%3D%3D%201)' \
    | python3 -c 'import json,sys
try:
    d=json.load(sys.stdin); r=d["data"]["result"]
    print(int(float(r[0]["value"][1])) if r else 0)
except Exception:
    print(0)' 2>/dev/null)
  kill "$pf" 2>/dev/null
  # En az dort: iki ortamda collector + api. HPA api'yi olceklediyse ya da yeniden
  # baslatmadan hemen sonra eski seriler hala lookback icindeyse bu sayi daha yuksek olur.
  if [ "${up:-0}" -ge 4 ]; then
    ok "toplanan hedef (collector+api, iki ortam) ($up)"
  else
    fail "toplanan hedef: en az 4 beklenir, gelen ${up:-0}"
  fi
fi

echo "--- HPA"
hpa=$(kubectl -n kesinti-prod get hpa api -o jsonpath='{.spec.minReplicas}-{.spec.maxReplicas}' 2>/dev/null)
bekle "PROD api HPA" "1-3" "${hpa:-yok}"

echo
if [ "$hatali" = "0" ]; then
  echo "Hepsi gecti."
  echo "  INT:     https://$INT_HOST"
  echo "  PROD:    https://$PROD_HOST"
  echo "  Argo CD: https://$ARGOCD_HOST"
  echo "  Sertifika kendi CA'mizdan: tarayici uyarisi normal."
else
  echo "$hatali kontrol basarisiz."
fi
exit $((hatali > 0))
