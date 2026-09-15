#!/usr/bin/env bash
# Butun stack'i sifirdan compose ile kurup nginx uzerinden uctan uca dener. CI'da (compose-smoke.yml) ve lokalde.
#
# - Collector canli kaynaklara gitmesin diye tarama kapali (COLLECTOR_SCHEDULING_ENABLED=false). Veri yolu
#   Redis Stream'e sahte bir olay yazilarak deneniyor: stream -> api -> PostgreSQL -> SSE (nginx uzerinden).
# - api container'i yeni bir IP ile yeniden olusturuluyor; nginx hala ulasiyor mu (2026-09-15'teki 502).
# - Ayri bir compose projesi (kesinti-smoke) ve kendi volume'leri: lokal veriye dokunmuyor, sonunda siliniyor.
#   Portlar ayni (3000, 8080...), o yuzden lokal stack acikken calismaz.
#
# Kullanim (repo kokunden): bash .github/scripts/compose-smoke.sh
set -euo pipefail
cd "$(dirname "$0")/../.."

export COMPOSE_PROJECT_NAME=${COMPOSE_PROJECT_NAME:-kesinti-smoke}
export COLLECTOR_SCHEDULING_ENABLED=false
BASE=http://localhost:3000

created_env=false
if [ ! -f .env ]; then
  cp .env.example .env
  sed -i "s/^POSTGRES_PASSWORD=.*/POSTGRES_PASSWORD=smoke-$(head -c 12 /dev/urandom | od -An -tx1 | tr -d ' \n')/" .env
  created_env=true
fi

cleanup() {
  [ -n "${SSE_PID:-}" ] && kill "$SSE_PID" 2>/dev/null || true
  docker rm -f smoke-ipgrab >/dev/null 2>&1 || true
  docker compose down -v --remove-orphans >/dev/null 2>&1 || true
  if [ "$created_env" = true ]; then rm -f .env; fi
}
trap cleanup EXIT

fail() {
  echo "HATA: $*" >&2
  docker compose ps >&2 || true
  docker compose logs --tail=40 api frontend >&2 || true
  exit 1
}
check() { # aciklama, beklenen, gelen
  if [ "$2" = "$3" ]; then echo "ok   $1"; else fail "$1: beklenen '$2', gelen '$3'"; fi
}
code() { curl -s -o /dev/null -w '%{http_code}' "$1"; }
ip_of() { docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$(docker compose ps -q "$1")"; }

echo "--- build + up (saglikli olana kadar)"
docker compose up -d --build --wait --wait-timeout 300

echo "--- imajlar root olmadan"
check "collector UID" 10001 "$(docker compose exec -T collector id -u)"
check "api UID" 10001 "$(docker compose exec -T api id -u)"
check "frontend UID" 101 "$(docker compose exec -T frontend id -u)"

echo "--- nginx uzerinden uclar"
check "frontend /" 200 "$(code "$BASE/")"
check "sinirlar /geo/ilceler.topo.json" 200 "$(code "$BASE/geo/ilceler.topo.json")"
check "ortam /env.json" '{"environment":"LOCAL"}' "$(curl -s "$BASE/env.json")"
check "api /api/outages" 200 "$(code "$BASE/api/outages?size=1")"
check "api /api/map/summary" 200 "$(code "$BASE/api/map/summary")"
check "api /api/sources" 200 "$(code "$BASE/api/sources")"

echo "--- veri yolu: stream -> api -> PostgreSQL -> SSE (nginx uzerinden)"
SSE=$(mktemp)
curl -sN -H 'Accept: text/event-stream' "$BASE/api/stream" > "$SSE" &
SSE_PID=$!
sleep 2
grep -q '^:bagli' "$SSE" || fail "SSE baglanti yorumu gelmedi"
echo "ok   SSE baglandi"
NOW=$(date -u +%Y-%m-%dT%H:%M:%SZ)
START=$(date -u -d '-5 min' +%Y-%m-%dT%H:%M:%SZ)
END=$(date -u -d '+1 hour' +%Y-%m-%dT%H:%M:%SZ)
PAYLOAD="{\"source\":\"SMOKE\",\"externalId\":null,\"type\":\"WATER\",\"planned\":false,\"il\":\"İZMİR\",\"ilce\":\"BORNOVA\",\"mahalleler\":[\"ERZENE\"],\"startsAt\":\"$START\",\"endsAt\":\"$END\",\"reason\":\"smoke test\",\"sourceUrl\":\"https://example.org\",\"lat\":null,\"lon\":null}"
docker compose exec -T redis redis-cli XADD outage-events '*' event NEW source SMOKE feed unplanned \
  dedupKey SMOKE:1 contentHash smoke scannedAt "$NOW" payload "$PAYLOAD" > /dev/null
for _ in $(seq 1 50); do grep -q '^event:outage.created' "$SSE" && break; sleep 0.2; done
grep -q '^event:outage.created' "$SSE" || fail "SSE outage.created gelmedi"
echo "ok   outage.created tarayiciya (SSE) geldi"
check "kayit api'de (source=SMOKE, aktif)" 1 "$(curl -s "$BASE/api/outages?source=SMOKE&active=true" | jq -r .total)"
check "harita ozetinde Bornova su" 1 "$(curl -s "$BASE/api/map/summary" | jq '[.districts[] | select(.ilceKey == "BORNOVA") | .byType.WATER] | add')"
kill "$SSE_PID" 2>/dev/null || true
SSE_PID=

echo "--- api yeni IP ile yeniden olusunca nginx hala ulasiyor mu"
NET=$(docker inspect -f '{{range $k, $v := .NetworkSettings.Networks}}{{$k}}{{end}}' "$(docker compose ps -q api)")
OLD_IP=$(ip_of api)
docker compose stop api > /dev/null 2>&1
# api'nin bosalttigi IP'yi baska bir container alsin: api yeni IP almak zorunda kalsin
docker run -d --rm --name smoke-ipgrab --network "$NET" --ip "$OLD_IP" alpine:3 sleep 300 > /dev/null
docker compose up -d --force-recreate --wait --wait-timeout 180 api > /dev/null 2>&1
NEW_IP=$(ip_of api)
[ "$OLD_IP" != "$NEW_IP" ] || fail "api ayni IP'yi aldi ($OLD_IP), deneme gecersiz"
c=000
for _ in $(seq 1 15); do c=$(code "$BASE/api/outages?size=1"); [ "$c" = 200 ] && break; sleep 2; done
check "api yeni IP'de ($OLD_IP -> $NEW_IP), frontend yeniden baslamadan" 200 "$c"

echo "smoke test gecti"
