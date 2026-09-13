-- Kesinti tablosu. Tarif: docs/tr/veri-modeli.md
-- Plandaki tabloya ek kolonlar:
--   content_hash      collector'in icerik hash'i; ayni olay tekrar gelirse satir degismez
--   gone_at           kaynak kaydi listeden kaldirdiginda (GONE)
--   il_key, ilce_key  Turkce karakterden bagimsiz anahtar (YUSUFELİ = YUSUFELI); filtre ve harita eslestirmesi
create table outage (
    id            uuid primary key default gen_random_uuid(),
    source        varchar(32)  not null,
    external_id   varchar(64),
    type          varchar(16)  not null check (type in ('ELECTRICITY', 'WATER', 'GAS')),
    planned       boolean      not null,
    il            varchar(64)  not null,
    ilce          varchar(64)  not null,
    il_key        varchar(64)  not null,
    ilce_key      varchar(64)  not null,
    mahalleler    text[]       not null default '{}',
    starts_at     timestamptz  not null,
    ends_at       timestamptz,
    reason        text,
    source_url    text         not null,
    lat           double precision,
    lon           double precision,
    dedup_key     varchar(128) not null,
    content_hash  varchar(64)  not null,
    first_seen_at timestamptz  not null,
    last_seen_at  timestamptz  not null,
    gone_at       timestamptz,
    constraint outage_lat_lon_together check ((lat is null) = (lon is null))
);

create unique index outage_dedup_key_uq on outage (dedup_key);
create unique index outage_source_external_id_uq on outage (source, external_id) where external_id is not null;

create index outage_district_idx on outage (il_key, ilce_key);
create index outage_starts_at_idx on outage (starts_at);
create index outage_ends_at_idx on outage (ends_at);
