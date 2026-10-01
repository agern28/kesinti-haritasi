#!/usr/bin/env python3
"""gitops/apps altindaki Argo CD Application'larini repoya karsi dogrular.

Argo CD bir yolu yanlis yazdigimizi ancak senkronizasyonda soyluyor; bu kontrol
aynisini CI'da yapiyor: isaret edilen chart var mi, values dosyasi var mi,
hedef namespace tanidik mi.

Kullanim (repo kokunden): python3 .github/scripts/check-gitops.py
"""
import pathlib
import sys

import yaml

KOK = pathlib.Path(__file__).resolve().parents[2]
APPS = KOK / "gitops" / "apps"
# Terraform'un olusturdugu namespace'ler (infra/terraform/local/variables.tf) ve argocd.
BILINEN_NAMESPACE = {"kesinti-int", "kesinti-prod", "kesinti-data", "argocd"}

hatalar: list[str] = []


def kontrol_kaynak(ad: str, kaynak: dict) -> None:
    yol = kaynak.get("path")
    if yol:
        chart = KOK / yol / "Chart.yaml"
        if not chart.is_file():
            hatalar.append(f"{ad}: {yol} altinda Chart.yaml yok")
    for values in kaynak.get("helm", {}).get("valueFiles", []):
        # Cok kaynakli Application'da values dosyasi $values/ ile baslar.
        if not values.startswith("$values/"):
            hatalar.append(f"{ad}: values dosyasi $values/ ile baslamiyor: {values}")
            continue
        dosya = KOK / values[len("$values/"):]
        if not dosya.is_file():
            hatalar.append(f"{ad}: values dosyasi yok: {values}")


def main() -> int:
    dosyalar = sorted(APPS.glob("*.yaml"))
    if not dosyalar:
        print(f"HATA: {APPS} altinda Application bulunamadi")
        return 1

    for dosya in dosyalar:
        belge = yaml.safe_load(dosya.read_text(encoding="utf-8"))
        ad = dosya.name
        if belge.get("kind") != "Application":
            hatalar.append(f"{ad}: kind Application degil ({belge.get('kind')})")
            continue
        if belge.get("metadata", {}).get("namespace") != "argocd":
            hatalar.append(f"{ad}: Application argocd namespace'inde olmali")

        spec = belge.get("spec", {})
        hedef = spec.get("destination", {}).get("namespace")
        if hedef not in BILINEN_NAMESPACE:
            hatalar.append(f"{ad}: bilinmeyen hedef namespace: {hedef}")

        kaynaklar = spec.get("sources") or ([spec["source"]] if "source" in spec else [])
        if not kaynaklar:
            hatalar.append(f"{ad}: source ya da sources yok")
        for kaynak in kaynaklar:
            kontrol_kaynak(ad, kaynak)

        print(f"ok   {ad} -> {hedef}")

    if hatalar:
        print()
        for hata in hatalar:
            print(f"HATA {hata}")
        return 1

    print(f"\n{len(dosyalar)} Application dogrulandi")
    return 0


if __name__ == "__main__":
    sys.exit(main())
