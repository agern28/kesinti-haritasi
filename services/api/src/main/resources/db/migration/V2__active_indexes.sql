-- Aktif kesinti sorgulari icin kismi indeksler. Kaynaktan kalkmis kayitlar (gone_at not null) ne
-- listede ne harita ozetinde sayiliyor; indekse de girmiyorlar. 19 bin satirin 18 bini gecmis veri.
--   outage_active_starts_idx: /api/outages (starts_at desc siralamasi + active filtresi)
--   outage_active_district_idx: /api/map/summary (il/ilce bazinda tur ve planli/ariza sayilari)
create index outage_active_starts_idx on outage (starts_at desc, ends_at) where gone_at is null;
create index outage_active_district_idx on outage (il_key, ilce_key, type, planned) where gone_at is null;
