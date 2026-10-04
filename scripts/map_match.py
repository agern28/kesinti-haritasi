#!/usr/bin/env python3
"""Veritabanindaki il/ilce ikililerini harita sinir dosyasiyla karsilastirir.

Eslesmeyen bir ilce, o kesintilerin haritada hicbir poligona dusmedigi anlamina gelir.
Yeni vakalar cikarsa collector'daki Districts sinifina (takma ad tablosu) eklenir.

Kullanim (repo kokunden): bash scripts/map-match.sh   ya da   make map-match
stdin: "il_key|ilce_key|source|adet" satirlari (map-match.sh psql ile uretiyor).
"""
import json
import pathlib
import re
import sys

GEO = pathlib.Path(__file__).resolve().parents[1] / "frontend/public/geo/ilceler.topo.json"


def compact(deger: str) -> str:
    return re.sub(r"\s+", "", deger or "")


def kimlik(il_key: str, ilce_key: str) -> str:
    """frontend/src/lib/districts.js ile ayni kural: bosluklar atilir, MERKEZ il adina doner."""
    il, ilce = compact(il_key), compact(ilce_key)
    return f"{il}|{il if ilce == 'MERKEZ' else ilce}"


def poligonlar() -> set[str]:
    geo = json.loads(GEO.read_text(encoding="utf-8"))
    return {
        g["properties"]["id"]
        for obj in geo["objects"].values()
        for g in obj.get("geometries", [])
        if g.get("properties", {}).get("id")
    }


def main() -> int:
    poligon = poligonlar()
    toplam = 0
    eksik: dict[tuple[str, str, str], int] = {}

    for satir in sys.stdin:
        satir = satir.strip()
        if not satir:
            continue
        try:
            il, ilce, kaynak, adet = satir.split("|")
            adet = int(adet)
        except ValueError:
            print(f"  satir okunamadi: {satir[:60]}")
            continue
        toplam += adet
        if kimlik(il, ilce) not in poligon:
            anahtar = (kaynak, il, ilce)
            eksik[anahtar] = eksik.get(anahtar, 0) + adet

    print(f"poligon: {len(poligon)}  |  kayit: {toplam}")
    if not eksik:
        print("eslesmeyen ilce yok: butun kesintiler haritada bir poligona dusuyor")
        return 0

    oran = 100 * sum(eksik.values()) / toplam if toplam else 0
    print(f"eslesmeyen grup: {len(eksik)}  |  eslesmeyen kayit: {sum(eksik.values())} ({oran:.1f}%)")
    print()
    for (kaynak, il, ilce), adet in sorted(eksik.items(), key=lambda x: -x[1]):
        print(f"  {kaynak:<7} {il:<12} {ilce:<28} {adet} kayit")
    print()
    print("Duzeltme: services/collector/.../normalize/Districts.java (takma ad tablosu ve kurallar)")
    return 1


if __name__ == "__main__":
    sys.exit(main())
