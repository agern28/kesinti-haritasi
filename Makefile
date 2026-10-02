# Lokal gelistirme kisayollari. Hepsi repo kokunden calisir: make up, make check ...
# Gereken: docker + docker compose. Testler icin ayrica Java 21 / Maven ve Node 22.12+.

SHELL := /usr/bin/env bash
.DEFAULT_GOAL := help

# k3d sudo olmadan ~/.local/bin'e kuruluyor; login shell olmadan da bulunsun.
export PATH := $(HOME)/.local/bin:$(PATH)

CLUSTER := kesinti
TF_DIR := infra/terraform/local

.PHONY: help env up build down stop restart ps logs logs-collector logs-api check smoke reset test \
	cluster-up cluster-down cluster-status cluster-check bootstrap wait-traefik \
	argocd-password argocd-apps argocd-refresh \
	grafana-password alerts prometheus alertmanager alarm-testi alarm-testi-bitir

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

# --- Kubernetes (lokal k3s kumesi) ---

cluster-up: ## Lokal k3s kumesini kurar (k3d) ve kume ustu kurulumu yapar
	k3d cluster create --config infra/k3d/cluster.yaml
	@$(MAKE) --no-print-directory wait-traefik
	@$(MAKE) --no-print-directory bootstrap

wait-traefik: ## Traefik'in k3s icindeki kurulum job'i bitene kadar bekler
	@echo "Traefik kurulumu bekleniyor..."
	@for i in $$(seq 1 60); do \
		kubectl -n kube-system get deploy traefik >/dev/null 2>&1 && break; \
		sleep 5; \
	done
	@kubectl -n kube-system rollout status deploy/traefik --timeout=300s

bootstrap: ## Namespace'ler, cert-manager ve ClusterIssuer'lar (terraform apply)
	cd $(TF_DIR) && terraform init -input=false -no-color && terraform apply -input=false -auto-approve -no-color
	@$(MAKE) --no-print-directory cluster-status

cluster-down: ## Lokal kumeyi tamamen siler (uygulama verisi compose'da, ona dokunmaz)
	k3d cluster delete $(CLUSTER)

cluster-status: ## Kume durumu: dugumler, pod'lar, issuer'lar, Argo CD applicationlari
	@kubectl get nodes
	@echo
	@kubectl get pods -A
	@echo
	@kubectl get clusterissuers
	@echo
	@kubectl -n argocd get applications 2>/dev/null || true

cluster-check: ## Kumedeki kurulumu uctan uca dener (Argo CD, iki ortam, sertifikalar, SSE)
	@bash scripts/cluster-check.sh

argocd-password: ## Argo CD admin parolasini yazdirir (ilk kurulum secret'i)
	@kubectl -n argocd get secret argocd-initial-admin-secret \
		-o go-template='{{index .data "password" | base64decode}}{{"\n"}}'

argocd-apps: ## Argo CD applicationlarinin durumu
	@kubectl -n argocd get applications \
		-o custom-columns='AD:.metadata.name,SYNC:.status.sync.status,SAGLIK:.status.health.status,REVISION:.status.sync.revisions[0]'

argocd-refresh: ## Argo CD'ye repoyu hemen kontrol ettirir (varsayilan dongu 3 dakika)
	@kubectl -n argocd annotate applications --all argocd.argoproj.io/refresh=hard --overwrite

# --- Izleme ---

grafana-password: ## Grafana admin parolasini yazdirir
	@kubectl -n monitoring get secret monitoring-grafana \
		-o go-template='{{index .data "admin-password" | base64decode}}{{"\n"}}'

alerts: ## Alarm kurallarinin durumu ve Alertmanager'daki aktif alarmlar
	@bash scripts/alerts.sh

prometheus: ## Prometheus arayuzunu localhost:9090'a baglar (Ctrl+C ile biter)
	kubectl -n monitoring port-forward svc/monitoring-kube-prometheus-prometheus 9090:9090

alertmanager: ## Alertmanager arayuzunu localhost:9093'e baglar (Ctrl+C ile biter)
	kubectl -n monitoring port-forward svc/monitoring-kube-prometheus-alertmanager 9093:9093

alarm-testi: ## Alarm denemesi: PROD collector'in internet cikisini keser (bitirmek icin alarm-testi-bitir)
	kubectl apply -f scripts/alarm-testi-networkpolicy.yaml
	@echo
	@echo "Collector ayakta kaliyor ama kaynak sitelere ulasamiyor."
	@echo "  ~15 dakika sonra: KesintiTaramaHatasiArtiyor"
	@echo "  ~35 dakika sonra: KesintiArizaTaramasiDurdu"
	@echo "Izlemek icin: make alerts"

alarm-testi-bitir: ## Alarm denemesini bitirir, internet cikisini geri acar
	kubectl delete -f scripts/alarm-testi-networkpolicy.yaml
	@echo "Ilk basarili taramayla alarmlar kendiliginden kapanir (birkac dakika)."
