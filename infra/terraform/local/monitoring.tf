# Izleme yigini: kube-prometheus-stack (Prometheus, Alertmanager, Grafana, kube-state-metrics,
# node-exporter). Kume seviyesi bir bilesen oldugu icin Terraform kuruyor; bizim kendi
# objelerimiz (ServiceMonitor, alarm kurallari, panolar) helm/monitoring chart'inda ve onu
# Argo CD kuruyor.
#
# Alertmanager'in yonlendirmesi de burada, chart'ta degil: Telegram token'i ve chat id
# terraform.tfvars'tan geliyor ve repoya girmemeleri gerekiyor.
#
# 2 vCPU / 4 GB hedefi: k3s'te olmayan bilesenlerin izlenmesi kapali, retention kisa,
# her bilesene bellek siniri verildi.

resource "kubernetes_namespace_v1" "monitoring" {
  metadata {
    name = "monitoring"
    labels = {
      "app.kubernetes.io/part-of"   = "kesinti-haritasi"
      "kesinti-haritasi/managed-by" = "terraform"
    }
  }
}

resource "random_password" "grafana_admin" {
  length  = 24
  special = false
}

# Telegram bot token ve chat id: repoda degil, tfvars'tan. Bos birakilabilir; o zaman
# Alertmanager'a Telegram alicisi eklenmiyor ve alarmlar yalnizca arayuzde gorunuyor.
resource "kubernetes_secret_v1" "telegram" {
  metadata {
    name      = "kesinti-telegram"
    namespace = kubernetes_namespace_v1.monitoring.metadata[0].name
  }

  data = {
    # Alertmanager bu dosyayi bot_token_file olarak okuyor.
    "bot-token" = var.telegram_bot_token
  }

  type = "Opaque"
}

locals {
  telegram_kurulu = var.telegram_bot_token != "" && var.telegram_chat_id != ""

  # Alertmanager yonlendirmesi. Telegram yoksa alarmlar "bos" aliciya gidiyor:
  # arayuzde gorunuyorlar, hicbir yere gonderilmiyorlar.
  alertmanager_config = {
    route = {
      receiver       = local.telegram_kurulu ? "telegram" : "bos"
      group_by       = ["alertname", "source", "feed"]
      group_wait     = "30s"
      group_interval = "5m"
      # Cozulmeyen alarm 4 saatte bir hatirlatiliyor.
      repeat_interval = "4h"
      routes = [
        # Prometheus'un kendi "izleme ayakta" alarmini gondermenin anlami yok.
        {
          receiver = "bos"
          matchers = ["alertname = Watchdog"]
        }
      ]
    }
    receivers = concat(
      [{ name = "bos" }],
      local.telegram_kurulu ? [{
        name = "telegram"
        telegram_configs = [{
          bot_token_file = "/etc/alertmanager/secrets/kesinti-telegram/bot-token"
          chat_id        = tonumber(var.telegram_chat_id)
          send_resolved  = true
          parse_mode     = ""
          message        = <<-EOT
            {{ if eq .Status "firing" }}ALARM{{ else }}COZULDU{{ end }}: {{ .CommonLabels.alertname }}
            {{ range .Alerts }}{{ .Annotations.summary }}
            {{ .Annotations.description }}
            {{ end }}
          EOT
        }]
      }] : []
    )
  }
}

resource "helm_release" "kube_prometheus_stack" {
  name       = "monitoring"
  repository = "https://prometheus-community.github.io/helm-charts"
  chart      = "kube-prometheus-stack"
  version    = var.kube_prometheus_version
  namespace  = kubernetes_namespace_v1.monitoring.metadata[0].name

  values = [yamlencode({
    alertmanager = {
      config = local.alertmanager_config
      alertmanagerSpec = {
        # Token dosyasi /etc/alertmanager/secrets/kesinti-telegram/ altina mount ediliyor.
        secrets = [kubernetes_secret_v1.telegram.metadata[0].name]
      }
    }
  })]

  set = [
    # --- k3s'te bu bilesenler ayri servis olarak yok; izlenmeye kalkinca hep "down" gorunurler
    { name = "kubeEtcd.enabled", value = "false" },
    { name = "kubeControllerManager.enabled", value = "false" },
    { name = "kubeScheduler.enabled", value = "false" },
    { name = "kubeProxy.enabled", value = "false" },
    { name = "defaultRules.rules.etcd", value = "false" },
    { name = "defaultRules.rules.kubeControllerManager", value = "false" },
    { name = "defaultRules.rules.kubeSchedulerAlerting", value = "false" },
    { name = "defaultRules.rules.kubeProxy", value = "false" },
    { name = "windowsMonitoring.enabled", value = "false" },

    # --- Prometheus
    # Kendi ServiceMonitor ve PrometheusRule'larimiz baska bir Helm release'inden geliyor;
    # bu seciciler kapatilmazsa Prometheus sadece bu release'in etiketlediklerini topluyor.
    { name = "prometheus.prometheusSpec.serviceMonitorSelectorNilUsesHelmValues", value = "false" },
    { name = "prometheus.prometheusSpec.podMonitorSelectorNilUsesHelmValues", value = "false" },
    { name = "prometheus.prometheusSpec.ruleSelectorNilUsesHelmValues", value = "false" },
    { name = "prometheus.prometheusSpec.probeSelectorNilUsesHelmValues", value = "false" },
    { name = "prometheus.prometheusSpec.scrapeConfigSelectorNilUsesHelmValues", value = "false" },
    { name = "prometheus.prometheusSpec.retention", value = var.prometheus_retention },
    { name = "prometheus.prometheusSpec.retentionSize", value = var.prometheus_retention_size },
    { name = "prometheus.prometheusSpec.scrapeInterval", value = "30s" },
    { name = "prometheus.prometheusSpec.resources.requests.cpu", value = "100m" },
    { name = "prometheus.prometheusSpec.resources.requests.memory", value = "384Mi" },
    { name = "prometheus.prometheusSpec.resources.limits.memory", value = "1Gi" },
    { name = "prometheus.prometheusSpec.storageSpec.volumeClaimTemplate.spec.accessModes[0]", value = "ReadWriteOnce" },
    { name = "prometheus.prometheusSpec.storageSpec.volumeClaimTemplate.spec.resources.requests.storage", value = "4Gi" },

    # --- Alertmanager
    { name = "alertmanager.alertmanagerSpec.resources.requests.memory", value = "48Mi" },
    { name = "alertmanager.alertmanagerSpec.resources.limits.memory", value = "128Mi" },
    { name = "alertmanager.alertmanagerSpec.storage.volumeClaimTemplate.spec.accessModes[0]", value = "ReadWriteOnce" },
    { name = "alertmanager.alertmanagerSpec.storage.volumeClaimTemplate.spec.resources.requests.storage", value = "1Gi" },

    # --- Grafana
    { name = "grafana.adminPassword", value = random_password.grafana_admin.result },
    { name = "grafana.resources.requests.cpu", value = "50m" },
    { name = "grafana.resources.requests.memory", value = "160Mi" },
    # 256Mi ile acilisi tamamlayamadi; Grafana 12 acilista cok sayida API kaydediyor.
    { name = "grafana.resources.limits.memory", value = "384Mi" },
    # Ayni sebeple probe'lar gevsetildi: 256Mi'lik ilk denemede liveness probe Grafana'yi
    # henuz 3000'i dinlemeye baslamadan oldurdu (bir kez restart).
    { name = "grafana.livenessProbe.initialDelaySeconds", value = "120" },
    { name = "grafana.livenessProbe.failureThreshold", value = "15" },
    { name = "grafana.readinessProbe.initialDelaySeconds", value = "30" },
    { name = "grafana.readinessProbe.failureThreshold", value = "20" },
    # Panolar repoda: sidecar, grafana_dashboard etiketli ConfigMap'leri okuyor.
    { name = "grafana.sidecar.dashboards.enabled", value = "true" },
    { name = "grafana.sidecar.dashboards.searchNamespace", value = "ALL" },
    { name = "grafana.sidecar.dashboards.label", value = "grafana_dashboard" },
    # Panolar "Kesinti Haritasi" klasorunde toplanir: ConfigMap'teki
    # k8s-sidecar-target-directory annotation'i ancak bu iki ayarla klasore donusuyor.
    { name = "grafana.sidecar.dashboards.folderAnnotation", value = "k8s-sidecar-target-directory" },
    { name = "grafana.sidecar.dashboards.provider.foldersFromFilesStructure", value = "true" },
    { name = "grafana.sidecar.resources.requests.memory", value = "48Mi" },
    { name = "grafana.sidecar.resources.limits.memory", value = "128Mi" },
    { name = "grafana.ingress.enabled", value = "true" },
    { name = "grafana.ingress.ingressClassName", value = "traefik" },
    { name = "grafana.ingress.hosts[0]", value = var.grafana_host },
    { name = "grafana.ingress.annotations.cert-manager\\.io/cluster-issuer", value = "kesinti-ca" },
    { name = "grafana.ingress.tls[0].secretName", value = "grafana-tls" },
    { name = "grafana.ingress.tls[0].hosts[0]", value = var.grafana_host },
    { name = "grafana.defaultDashboardsTimezone", value = "Europe/Istanbul" },

    # --- kube-state-metrics ve node-exporter
    { name = "kube-state-metrics.resources.requests.memory", value = "48Mi" },
    { name = "kube-state-metrics.resources.limits.memory", value = "128Mi" },
    { name = "prometheus-node-exporter.resources.requests.memory", value = "24Mi" },
    { name = "prometheus-node-exporter.resources.limits.memory", value = "64Mi" },

    # --- Operator
    { name = "prometheusOperator.resources.requests.memory", value = "64Mi" },
    { name = "prometheusOperator.resources.limits.memory", value = "192Mi" },
  ]

  wait          = true
  wait_for_jobs = true
  timeout       = 1200
}
