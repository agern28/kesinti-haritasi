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
  description = "Uygulama ortamlari. Faz 7'de Argo CD bu namespace'lere kuracak."
  type        = list(string)
  default     = ["kesinti-int", "kesinti-prod"]
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
