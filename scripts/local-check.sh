#!/usr/bin/env bash
# Lokalde calisan stack'i bastan sona dener: container'lar, nginx, api yollari, veri yolu, kaynak sagligi.
# CI'daki compose-smoke.sh stack'i sifirdan kurar; bu script ise zaten acik olan stack'e bakar.
#
# Kullanim (repo kokunden): bash scripts/local-check.sh   ya da   make check
set -uo pipefail
cd "$(dirname "$0")/.."

BASE=${BASE:-http://localhost:3000}
API=${API:-http://localhost:8080}
hatali=0

ok()   { echo "ok    $1"; }
fail() { echo "HATA  $1"; hatali=$((hatali + 1)); }

bekle() { # aciklama, beklenen, gelen
  if [ "$2" = "$3" ]; then ok "$1 ($3)"; else fail "$1: beklenen '$2', gelen '$3'"; fi
}

kod() { curl -s -o /dev/null -w '%{http_code}' --max-time 20 "$1"; }

echo "--- container'lar"
calisan=$(docker compose ps --status running --format '{{.Service}}' | sort | tr '\n' ' ')
bekle "5 servis calisiyor" "api collector frontend postgres redis " "$calisan"
saglikli=$(docker compose ps --format '{{.Health}}' | grep -c healthy)
bekle "5 servis healthy" "5" "$saglikli"

echo "--- frontend (nginx)"
bekle "ana sayfa" "200" "$(kod "$BASE/")"
bekle "healthz" "200" "$(kod "$BASE/healthz")"
ortam=$(curl -s --max-time 20 "$BASE/env.json")
bekle "env.json" '{"environment":"LOCAL"}' "$ortam"
geo_boyut=$(curl -s -o /dev/null -w '%{size_download}' --max-time 30 "$BASE/geo/ilceler.topo.json?v=1.0.0")
if [ "$geo_boyut" -gt 300000 ]; then ok "ilce sinirlari ($geo_boyut bayt)"; else fail "ilce sinirlari kucuk geldi: $geo_boyut bayt"; fi

echo "--- api (nginx uzerinden)"
bekle "harita ozeti" "200" "$(kod "$BASE/api/map/summary")"
bekle "kesinti listesi" "200" "$(kod "$BASE/api/outages?size=5")"
bekle "kaynak durumu" "200" "$(kod "$BASE/api/sources")"
bekle "derin sayfa reddediliyor" "400" "$(kod "$BASE/api/outages?page=2000&size=100")"
sinir=$(curl -s --max-time 30 "$BASE/api/outages?size=9999" | grep -o '"id"' | wc -l | tr -d ' ')
bekle "sayfa boyutu 500'e kirpiliyor" "500" "$sinir"

echo "--- canli akis (SSE)"
akis=$(curl -s --max-time 8 -H 'Accept: text/event-stream' "$BASE/api/stream" | head -c 200)
if [ -n "$akis" ]; then ok "stream veri gonderiyor"; else fail "stream'den 8 saniyede hic sey gelmedi"; fi

echo "--- veri"
satir=$(docker compose exec -T postgres psql -U "${POSTGRES_USER:-kesinti}" -d "${POSTGRES_DB:-kesinti}" -tAc 'select count(*) from outage;' 2>/dev/null | tr -d '\r')
if [ "${satir:-0}" -gt 0 ]; then ok "veritabaninda $satir kesinti kaydi"; else fail "veritabani bos ya da okunamadi"; fi
bellek=$(docker compose exec -T redis redis-cli info memory 2>/dev/null | sed -n 's/^used_memory_human:\(.*\)/\1/p' | tr -d '\r')
ok "redis bellek: ${bellek:-bilinmiyor}"

echo "--- kaynaklar"
kaynak_sayisi=$(curl -s --max-time 20 "$BASE/api/sources" | grep -o '"source"' | wc -l | tr -d ' ')
if [ "$kaynak_sayisi" -ge 5 ]; then ok "$kaynak_sayisi kaynak listeleniyor"; else fail "kaynak sayisi az: $kaynak_sayisi"; fi
eski=$(curl -s --max-time 20 "$BASE/api/sources" | grep -o '"stale":true' | wc -l | tr -d ' ')
if [ "$eski" = "0" ]; then
  ok "hicbir kaynak eski degil"
else
  echo "not   $eski kaynak eski (stale). Tarama araligindan uzun suredir veri gelmemis;"
  echo "      stack yeni acildiysa birkac dakika sonra duzelir, kalirsa: make logs-collector"
fi

echo
if [ "$hatali" = "0" ]; then
  echo "Hepsi gecti. Arayuz: $BASE   api: $API/actuator/health"
else
  echo "$hatali kontrol basarisiz. Loglar: make logs"
fi
exit $((hatali > 0))
