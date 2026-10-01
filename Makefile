# Lokal gelistirme kisayollari. Hepsi repo kokunden calisir: make up, make check ...
# Gereken: docker + docker compose. Testler icin ayrica Java 21 / Maven ve Node 22.12+.

SHELL := /usr/bin/env bash
.DEFAULT_GOAL := help

.PHONY: help env up build down stop restart ps logs logs-collector logs-api check smoke reset test

help: ## Komutlari listeler
	@echo "Kesinti Haritasi - lokal komutlar"
	@echo
	@grep -hE '^[a-z-]+:.*?## ' $(MAKEFILE_LIST) | sed -E 's/:.*## /|/' | awk -F'|' '{printf "  make %-16s %s\n", $$1, $$2}'
	@echo
	@echo "  Arayuz http://localhost:3000   api http://localhost:8080   collector http://localhost:8081"

env: ## .env yoksa .env.example'dan rastgele parolayla olusturur
	@if [ -f .env ]; then \
		echo ".env zaten var, dokunulmadi"; \
	else \
		cp .env.example .env; \
		sed -i "s/^POSTGRES_PASSWORD=.*/POSTGRES_PASSWORD=$$(head -c 18 /dev/urandom | od -An -tx1 | tr -d ' \n')/" .env; \
		echo ".env olusturuldu, parola rastgele uretildi"; \
	fi

up: env ## Stack'i ayaga kaldirir (gerekirse imajlari derler) ve saglikli olmasini bekler
	docker compose up -d --build --wait --wait-timeout 300
	@$(MAKE) --no-print-directory ps

build: env ## Imajlari yeniden derler, calistirmaz
	docker compose build

down: ## Stack'i durdurur ve container'lari siler (veri kalir)
	docker compose down

stop: ## Stack'i durdurur, container'lar kalir (bilgisayar yeniden baslayinca kendileri kalkmaz)
	docker compose stop

restart: ## Servisleri yeniden baslatir
	docker compose restart
	@$(MAKE) --no-print-directory ps

ps: ## Servislerin durumu
	@docker compose ps --format 'table {{.Service}}\t{{.Status}}\t{{.Ports}}'

logs: ## Butun loglar (cikmak icin Ctrl+C)
	docker compose logs -f --tail=100

logs-collector: ## Sadece collector loglari: hangi kaynak ne zaman tarandi
	docker compose logs -f --tail=100 collector

logs-api: ## Sadece api loglari
	docker compose logs -f --tail=100 api

check: ## Calisan stack'i uctan uca dener (nginx, api, SSE, veri, kaynaklar)
	@bash scripts/local-check.sh

smoke: ## Sifirdan kurup CI'daki uctan uca testi lokalde kosar (lokal stack kapali olmali)
	bash .github/scripts/compose-smoke.sh

reset: ## Veritabani ve Redis dahil her seyi siler, stack'i bastan kurar
	docker compose down -v
	@$(MAKE) --no-print-directory up

test: ## Servis ve frontend testleri (container disinda, Java ve Node gerekir)
	cd services/collector && mvn -q verify
	cd services/api && mvn -q verify
	cd frontend && npm test
