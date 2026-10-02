#!/usr/bin/env bash
# Alarmlarin su anki durumu. Prometheus'a gecici bir port-forward kurup scripts/alerts.py
# cagiriyor (Prometheus container'inda curl ya da wget yok, API'ye disaridan bakiliyor).
#
# Kullanim (repo kokunden): bash scripts/alerts.sh   ya da   make alerts
set -uo pipefail
cd "$(dirname "$0")/.."
PATH="$HOME/.local/bin:$PATH"

temizle() { [ -n "${PF:-}" ] && kill "$PF" 2>/dev/null; }
trap temizle EXIT

kubectl -n monitoring port-forward svc/monitoring-kube-prometheus-prometheus 9090:9090 >/dev/null 2>&1 &
PF=$!
sleep 5

python3 scripts/alerts.py
