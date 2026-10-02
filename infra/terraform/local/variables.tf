variable "kubeconfig_path" {
  description = "Kubeconfig dosyasi. k3d varsayilan olarak ~/.kube/config'i guncelliyor."
  type        = string
  default     = "~/.kube/config"
}

variable "kube_context" {
  description = "Kullanilacak context. k3d kume adinin onune k3d- ekliyor."
  type        = string
  default     = "k3d-kesinti"
}

variable "namespaces" {
  description = "Ortam namespace'leri ve veri katmaninin namespace'i. Argo CD bunlara kuruyor."
  type        = list(string)
  default     = ["kesinti-int", "kesinti-prod", "kesinti-data"]
}

variable "db_secret_name" {
  description = "PostgreSQL parolasinin durdugu secret. Chart'lar bu adi bekliyor."
  type        = string
  default     = "kesinti-db"
}

variable "argocd_version" {
  description = "Argo CD chart surumu. Sabit tutuluyor."
  type        = string
  default     = "10.9.6"
}

variable "kube_prometheus_version" {
  description = "kube-prometheus-stack chart surumu."
  type        = string
  default     = "91.8.2"
}

variable "prometheus_retention" {
  description = "Prometheus veri saklama suresi. Lokal kumede kisa tutuluyor."
  type        = string
  default     = "2d"
}

variable "prometheus_retention_size" {
  description = "Disk tarafindan sinir; retention suresinden once dolarsa eski veri silinir."
  type        = string
  default     = "3GiB"
}

variable "grafana_host" {
  description = "Grafana'nin Ingress adresi."
  type        = string
  default     = "grafana.localhost"
}

variable "telegram_bot_token" {
  description = <<-EOT
    Alarmlarin gidecegi Telegram botunun token'i. Repoya girmez: terraform.tfvars
    dosyasina yazilir (.gitignore'da). Bos birakilirsa Telegram alicisi kurulmaz,
    alarmlar yalnizca Alertmanager arayuzunde gorunur.
  EOT
  type        = string
  default     = ""
  sensitive   = true
}

variable "telegram_chat_id" {
  description = "Alarmlarin gidecegi Telegram sohbetinin id'si. Bos birakilabilir."
  type        = string
  default     = ""
  sensitive   = true
}

variable "cert_manager_version" {
  description = "cert-manager chart surumu. Sabit tutuluyor: kume her kurulusta ayni olsun."
  type        = string
  default     = "v1.21.2"
}

variable "cert_manager_memory_limit" {
  description = "cert-manager bilesenlerinin bellek siniri. Kume 4 GB'lik sunucuya da sigmali."
  type        = string
  default     = "128Mi"
}
