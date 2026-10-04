#!/usr/bin/env bash
# Kumedeki PROD veritabanindaki il/ilce ikililerini harita sinir dosyasiyla karsilastirir.
# Eslesmeyen varsa cikis kodu 1 olur ve hangi kaynaktan kac kayit oldugu yazilir.
#
# Kullanim (repo kokunden): bash scripts/map-match.sh   ya da   make map-match
#   NS=kesinti-int bash scripts/map-match.sh   -> INT veritabanina bakar
set -uo pipefail
cd "$(dirname "$0")/.."
PATH="$HOME/.local/bin:$PATH"

NS=${NS:-kesinti-prod}
DB=${DB:-kesinti_prod}
KULLANICI=${KULLANICI:-kesinti}

# Sadece kaynakta duran kayitlar (gone_at is null): harita da onlari gosteriyor.
# Kalkmis kayitlar tabloda kaliyor ve duzeltme oncesindeki eski adlari tasiyor.
kayitlar=$(kubectl -n kesinti-data exec postgres-0 -- psql -U "$KULLANICI" -d "$DB" -tAF'|' -c \
  "select il_key, ilce_key, source, count(*) from outage where gone_at is null group by 1,2,3 order by 4 desc;" 2>/dev/null)

if [ -z "$kayitlar" ]; then
  echo "Veritabanindan kayit alinamadi ($DB). Kume ayakta mi: make cluster-status"
  exit 2
fi

printf '%s\n' "$kayitlar" | python3 scripts/map_match.py
