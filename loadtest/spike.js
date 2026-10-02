// Ani trafik senaryosu: normal yuk, 10 kat sicrama, sonra dusus.
// Amac HPA'nin olceklenmesini ve ozet cache'inin etkisini olcmek (Faz 9).
//
// Calistirma (repo kokunden):
//   make loadtest                      -> scripts/loadtest.sh, HPA ve metrikleri de ornekler
//   k6 run loadtest/spike.js           -> sadece k6
//   BASE_URL=https://int.kesinti.localhost k6 run loadtest/spike.js
//
// Not: lokal kumede sertifika kendi CA'mizdan, bu yuzden insecureSkipTLSVerify.
// *.localhost adini k6'nin kendi hosts tablosu 127.0.0.1'e cozuyor.
import http from 'k6/http'
import { check, sleep } from 'k6'
import { Counter, Trend } from 'k6/metrics'

const BASE = __ENV.BASE_URL || 'https://kesinti.localhost'
const NORMAL = Number(__ENV.NORMAL_VUS || 10)
const SPIKE = Number(__ENV.SPIKE_VUS || 100)

// Ozet ve liste ayri olculuyor: cache'li uc ile veritabanina giden uc ayni grafikte karismasin.
const ozetSure = new Trend('ozet_sure', true)
const listeSure = new Trend('liste_sure', true)
const hataSayisi = new Counter('beklenmeyen_cevap')

export const options = {
  insecureSkipTLSVerify: true,
  hosts: {
    'kesinti.localhost': '127.0.0.1',
    'int.kesinti.localhost': '127.0.0.1',
  },
  stages: [
    { duration: '1m', target: NORMAL },   // normal yuk
    { duration: '30s', target: SPIKE },   // sicrama: 10 kat
    { duration: '2m', target: SPIKE },    // sicramada kal
    { duration: '30s', target: NORMAL },  // geri in
    { duration: '1m', target: NORMAL },   // HPA'nin kuculmesini beklemek icin degil, kuyruk bosalsin
  ],
  thresholds: {
    // Lokal kumede 2 vCPU paylasiliyor; esikler buna gore.
    'http_req_failed': ['rate<0.01'],
    'ozet_sure': ['p(95)<1500'],
    'liste_sure': ['p(95)<2500'],
  },
}

// Ilceye tiklamayi taklit eden ornek sorgular.
const ILCELER = [
  ['İSTANBUL', 'KADIKÖY'],
  ['İSTANBUL', 'ÜSKÜDAR'],
  ['İZMİR', 'BORNOVA'],
  ['ANTALYA', 'KEPEZ'],
  ['SİVAS', 'MERKEZ'],
  ['KAYSERİ', 'MELİKGAZİ'],
]

export default function () {
  // Harita acilisi: ozet. Gercek kullanimda en sik istenen uc, 10 dakika cache'li.
  const ozet = http.get(`${BASE}/api/map/summary`, { tags: { uc: 'ozet' } })
  ozetSure.add(ozet.timings.duration)
  if (!check(ozet, { 'ozet 200': (r) => r.status === 200 })) {
    hataSayisi.add(1)
  }

  // Kullanicilarin bir kismi ilceye tikliyor: bu sorgu veritabanina gidiyor.
  if (Math.random() < 0.4) {
    const [il, ilce] = ILCELER[Math.floor(Math.random() * ILCELER.length)]
    const liste = http.get(
      `${BASE}/api/outages?il=${encodeURIComponent(il)}&ilce=${encodeURIComponent(ilce)}&size=50`,
      { tags: { uc: 'liste' } },
    )
    listeSure.add(liste.timings.duration)
    if (!check(liste, { 'liste 200': (r) => r.status === 200 })) {
      hataSayisi.add(1)
    }
  }

  // Kaynak durumu kutusu, daha seyrek.
  if (Math.random() < 0.15) {
    const kaynaklar = http.get(`${BASE}/api/sources`, { tags: { uc: 'kaynaklar' } })
    if (!check(kaynaklar, { 'kaynaklar 200': (r) => r.status === 200 })) {
      hataSayisi.add(1)
    }
  }

  sleep(1 + Math.random())
}
