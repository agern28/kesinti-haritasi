#!/usr/bin/env bash
# Yeniden baslatmadan sonra kubelet sertifikasini duzeltir.
#
# Belirti: "kubectl exec" ya da "kubectl logs" bazi pod'larda su hatayi veriyor:
#   tls: failed to verify certificate: x509: certificate is valid for 172.19.0.3, not 172.19.0.4
#
# Sebep: Docker, WSL ya da makine yeniden baslayinca k3d container'larina farkli IP verebiliyor
# (bizde server ve agent IP'leri takas etti). Kubelet'in sunucu sertifikasi eski IP'ye yazili
# oldugu icin API sunucusu o dugume baglanamiyor. k3s sertifikayi acilista uretiyor, bu yuzden
# dosyayi silip dugumu yeniden baslatmak yeterli.
#
# Kullanim (repo kokunden): bash scripts/fix-kubelet-cert.sh   ya da   make cluster-fix-kubelet
set -uo pipefail
PATH="$HOME/.local/bin:$PATH"

KUME=${KUME:-kesinti}

dugumler=$(docker ps --filter "name=k3d-${KUME}-" --filter 'status=running' --format '{{.Names}}' | grep -vE 'serverlb|tools' || true)
if [ -z "$dugumler" ]; then
  echo "k3d dugumu bulunamadi (kume adi: $KUME). Kume ayakta mi: k3d cluster list"
  exit 2
fi

for dugum in $dugumler; do
  ip=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$dugum")
  san=$(docker exec "$dugum" sh -c 'cat /var/lib/rancher/k3s/agent/serving-kubelet.crt 2>/dev/null' \
        | openssl x509 -noout -ext subjectAltName 2>/dev/null | tr -d ' \n')
  if [ -z "$san" ]; then
    echo "$dugum: sertifika okunamadi, atlandi"
    continue
  fi
  if printf '%s' "$san" | grep -q "IPAddress:$ip"; then
    echo "$dugum: sertifika guncel ($ip), dokunulmadi"
    continue
  fi
  echo "$dugum: sertifika $ip icin gecerli degil, yeniden uretiliyor"
  docker exec "$dugum" rm -f /var/lib/rancher/k3s/agent/serving-kubelet.crt /var/lib/rancher/k3s/agent/serving-kubelet.key
  docker restart "$dugum" >/dev/null
done

echo "=== API sunucusu ve dugumler"
for i in $(seq 1 60); do
  hazir=$(kubectl get nodes --no-headers 2>/dev/null | grep -c ' Ready ')
  toplam=$(kubectl get nodes --no-headers 2>/dev/null | wc -l | tr -d ' ')
  echo "  [$i] Ready $hazir/${toplam:-?}"
  [ -n "$toplam" ] && [ "$toplam" -gt 0 ] && [ "$hazir" = "$toplam" ] && break
  sleep 5
done

echo "=== deneme"
if kubectl -n kesinti-data exec postgres-0 -- sh -c 'echo ok' >/dev/null 2>&1; then
  echo "  kubectl exec calisiyor"
else
  echo "  kubectl exec hala hata veriyor; pod'lar yerlesene kadar bekleyip tekrar dene (make cluster-status)"
fi
