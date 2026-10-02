#!/usr/bin/env python3
"""Alarm kurallarinin durumu ve Alertmanager'daki aktif alarmlar.

scripts/alerts.sh once Prometheus'a port-forward kuruyor, sonra bunu cagiriyor.
Dogrudan calistirmak isterseniz 9090 portunun bagli olmasi gerekir.
"""
import json
import subprocess
import sys
import urllib.error
import urllib.request

PROMETHEUS = "http://localhost:9090"
ISARET = {"firing": "ALARM ", "pending": "bekler", "inactive": "sakin "}


def kim(etiketler: dict) -> str:
    if "source" in etiketler:
        return "{}/{}".format(etiketler.get("source", ""), etiketler.get("feed", ""))
    return etiketler.get("namespace", "")


def kurallar() -> int:
    print("--- kendi alarm kurallarimiz")
    try:
        with urllib.request.urlopen(f"{PROMETHEUS}/api/v1/rules", timeout=20) as cevap:
            veri = json.load(cevap)
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as hata:
        print(f"  Prometheus okunamadi: {hata}")
        return 1

    bulundu = False
    for grup in veri["data"]["groups"]:
        if not grup["name"].startswith("kesinti"):
            continue
        for kural in grup["rules"]:
            bulundu = True
            durum = kural.get("state", "-")
            print("  {} {}".format(ISARET.get(durum, durum), kural["name"]))
            for alarm in kural.get("alerts", []):
                print("           -> {} ({}, {})".format(
                    kim(alarm["labels"]), alarm["state"], alarm["activeAt"][:19]))
    if not bulundu:
        print("  kural bulunamadi (helm/monitoring kurulu mu?)")
        return 1
    return 0


def alertmanager() -> None:
    print()
    print("--- Alertmanager'daki alarmlar")
    komut = [
        "kubectl", "-n", "monitoring", "exec",
        "statefulset/alertmanager-monitoring-kube-prometheus-alertmanager",
        "-c", "alertmanager", "--",
        "wget", "-qO-", "http://localhost:9093/api/v2/alerts",
    ]
    try:
        cikti = subprocess.run(komut, capture_output=True, text=True, timeout=60, check=True).stdout
        alarmlar = json.loads(cikti)
    except (subprocess.SubprocessError, json.JSONDecodeError) as hata:
        print(f"  Alertmanager okunamadi: {hata}")
        return

    if not alarmlar:
        print("  aktif alarm yok")
    for alarm in alarmlar:
        etiketler = alarm["labels"]
        print("  {} {} {}".format(
            etiketler.get("alertname"), alarm["status"]["state"], kim(etiketler)))


if __name__ == "__main__":
    kod = kurallar()
    alertmanager()
    sys.exit(kod)
