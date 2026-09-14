#!/usr/bin/env python3
"""Ilce sinirlarini uretir: public/geo/ilceler.topo.json

Kaynaklar:
- Sinirlar: OCHA HDX "Türkiye - Subnational Administrative Boundaries" (COD-AB). Veriyi Harita Genel
  Komutanligi saglamis, lisans CC BY-IGO. https://data.humdata.org/dataset/cod-ab-tur
- Il ve ilce adlarinin Turkce yazimi: Wikidata (CC0). HDX'teki adlarda "ı" harfi "i" olmus
  (Kadiköy, Ağri) ve bazi "i"lerin arkasinda ayri birlesik nokta (U+0307) var. Eslestirme anahtari
  ikisinde de ayni oldugu icin ad Wikidata'daki yazimla degistiriliyor.

Cikti TopoJSON, nesne adi "ilceler", ozellikler:
- id: "IL|ILCE" anahtari (frontend/src/lib/districts.js'teki districtId ile ayni kural)
- il, ilce: gosterilecek adlar
Sadelestirme mapshaper ile (%6, keep-shapes): 29 MB -> ~490 KB.

Kullanim (frontend dizininden, python3 ve npx gerekiyor):
    python3 scripts/ilceler.py [--cache DIZIN]
Indirilen dosyalar cache dizininde tutulur, ikinci calistirmada tekrar indirilmez.
"""
import argparse
import collections
import json
import os
import re
import subprocess
import sys
import tempfile
import unicodedata
import urllib.parse
import urllib.request
import zipfile

HDX_URL = ('https://data.humdata.org/dataset/d74086a0-f398-4474-9e12-1b9a70907bd0/resource/'
           '470bd810-2240-4ce0-b5c4-17434112ce41/download/tur_admin_boundaries.geojson.zip')
SPARQL_URL = 'https://query.wikidata.org/sparql'
USER_AGENT = 'kesinti-haritasi/0.1 (https://github.com/agern28/kesinti-haritasi)'
MAPSHAPER = 'mapshaper@0.7.61'
SIMPLIFY = '6%'
OUT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'public', 'geo', 'ilceler.topo.json'))

# Illerin alt birimleri (P150). Il etiketleri "Adana ili" gibi geliyor.
QUERY = '''SELECT ?p ?pLabel ?d ?dLabel WHERE {
  ?p wdt:P31 wd:Q48336 ; wdt:P150 ?d .
  FILTER NOT EXISTS { ?d wdt:P576 ?end }
  SERVICE wikibase:label { bd:serviceParam wikibase:language "tr". }
}'''

# Wikidata'da ilin alt birimi olarak bulunamayan ve HDX yaziminda "ı" harfi kaybolan ilceler.
NAME_FIXES = {
    ('KAYSERI', 'YAHYALI'): 'Yahyalı',
    ('TEKIRDAG', 'MURATLI'): 'Muratlı',
}

SUFFIX = re.compile(r'\s+(\([^)]*\)|ili|İli|ilçesi|İlçesi|ilçe|İlçe)$')


def key(s):
    """Turkce buyuk harf, NFD ile isaretler atilir, harf/rakam disi atilir. api Names.key'in bosluksuz hali."""
    s = (s or '').replace('i', 'İ').replace('ı', 'I').upper()
    s = ''.join(c for c in unicodedata.normalize('NFD', s) if not unicodedata.combining(c))
    return re.sub(r'[^0-9A-Z]', '', s)


def strip_suffix(label):
    """'Yeşilyurt İlçesi (Tokat)' -> 'Yeşilyurt', 'Adana ili' -> 'Adana'."""
    label = label.strip()
    while True:
        cut = SUFFIX.sub('', label)
        if cut == label:
            return label
        label = cut


def clean(name):
    """HDX yazimindaki ayri birlesik noktayi atar: 'Elbi̇stan' -> 'Elbistan'."""
    return unicodedata.normalize('NFC', name.replace('̇', ''))


def prefer(labels):
    """Ayni anahtarli birden fazla etiket ('Kahta'/'Kâhta', 'Marmaraereğlisi'/'Marmara Ereğlisi'):
    bosluksuz olan, sonra Turkce karakteri daha cok olan."""
    return sorted(labels, key=lambda s: (s.count(' '), -sum(1 for c in s if ord(c) > 127), s))[0]


def download(url, path, accept=None):
    headers = {'User-Agent': USER_AGENT}
    if accept:
        headers['Accept'] = accept
    print('indiriliyor:', url.split('?')[0])
    with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=300) as res, open(path, 'wb') as out:
        out.write(res.read())


def load_hdx(cache):
    path = os.path.join(cache, 'tur_admin_boundaries.geojson.zip')
    if not os.path.exists(path):
        download(HDX_URL, path)
    with zipfile.ZipFile(path) as z:
        name = next(n for n in z.namelist() if n.endswith('tur_admin2.geojson'))
        with z.open(name) as f:
            return json.load(f)


def load_wikidata(cache):
    path = os.path.join(cache, 'wikidata-ilceler.json')
    if not os.path.exists(path):
        download(SPARQL_URL + '?' + urllib.parse.urlencode({'query': QUERY}), path,
                 accept='application/sparql-results+json')
    with open(path, encoding='utf-8') as f:
        rows = json.load(f)['results']['bindings']
    provinces = {}
    districts = collections.defaultdict(set)
    for r in rows:
        p = strip_suffix(r['pLabel']['value'])
        d = strip_suffix(r['dLabel']['value'])
        provinces[key(p)] = p
        districts[(key(p), key(d))].add(d)
    return provinces, {k: prefer(v) for k, v in districts.items()}


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument('--cache', default=os.path.join(tempfile.gettempdir(), 'kesinti-geo'))
    args = ap.parse_args()
    os.makedirs(args.cache, exist_ok=True)

    hdx = load_hdx(args.cache)
    provinces, names = load_wikidata(args.cache)

    features = []
    fallback = []
    for f in hdx['features']:
        p = f['properties']
        il_key, ilce_key = key(p['adm1_name1']), key(p['adm2_name1'])
        ilce = names.get((il_key, ilce_key)) or NAME_FIXES.get((il_key, ilce_key))
        if not ilce:
            ilce = clean(p['adm2_name1'])
            fallback.append(ilce)
        features.append({
            'type': 'Feature',
            'geometry': f['geometry'],
            'properties': {'id': f'{il_key}|{ilce_key}', 'il': provinces.get(il_key) or clean(p['adm1_name1']), 'ilce': ilce},
        })

    dup = [k for k, n in collections.Counter(x['properties']['id'] for x in features).items() if n > 1]
    if dup:
        sys.exit(f'ayni kimlikle birden fazla ilce: {dup}')
    il_count = len({x['properties']['id'].split('|')[0] for x in features})
    print(f'{len(features)} ilce, {il_count} il. Adi HDX yaziminden temizlenenler: {fallback}')

    with tempfile.TemporaryDirectory() as tmp:
        src = os.path.join(tmp, 'ilceler.geojson')
        with open(src, 'w', encoding='utf-8') as out:
            json.dump({'type': 'FeatureCollection', 'features': features}, out, ensure_ascii=False)
        os.makedirs(os.path.dirname(OUT), exist_ok=True)
        subprocess.run(['npx', '-y', MAPSHAPER, src, '-simplify', SIMPLIFY, 'keep-shapes', '-clean',
                        '-rename-layers', 'ilceler', '-o', 'format=topojson', 'quantization=1e5', OUT], check=True)
    print(OUT, os.path.getsize(OUT), 'bayt')


if __name__ == '__main__':
    main()
