# Argo CD: kumeye kuruluyor, sonra kok Application repodaki gitops/apps'i izliyor.
# Uygulamalari Terraform kurmuyor; Terraform sadece Argo CD'yi ve kok Application'i kuruyor.

resource "helm_release" "argocd" {
  name             = "argocd"
  repository       = "https://argoproj.github.io/argo-helm"
  chart            = "argo-cd"
  version          = var.argocd_version
  namespace        = "argocd"
  create_namespace = true

  set = [
    # TLS'i Traefik yapiyor (Ingress'te cert-manager sertifikasi), kume icinde http.
    {
      name  = "configs.params.server\\.insecure"
      value = "true"
    },
    # Kullanilmayan bilesenler kapali: 4 GB'lik bir sunucuda da sigmali.
    {
      name  = "dex.enabled"
      value = "false"
    },
    {
      name  = "notifications.enabled"
      value = "false"
    },
    {
      name  = "applicationSet.enabled"
      value = "false"
    },
    # Kaynak sinirlari
    {
      name  = "controller.resources.requests.cpu"
      value = "100m"
    },
    {
      name  = "controller.resources.requests.memory"
      value = "256Mi"
    },
    {
      name  = "controller.resources.limits.memory"
      value = "512Mi"
    },
    {
      name  = "server.resources.requests.cpu"
      value = "50m"
    },
    {
      name  = "server.resources.requests.memory"
      value = "128Mi"
    },
    {
      name  = "server.resources.limits.memory"
      value = "256Mi"
    },
    {
      name  = "repoServer.resources.requests.cpu"
      value = "50m"
    },
    {
      name  = "repoServer.resources.requests.memory"
      value = "128Mi"
    },
    {
      name  = "repoServer.resources.limits.memory"
      value = "384Mi"
    },
    {
      name  = "redis.resources.requests.memory"
      value = "64Mi"
    },
    {
      name  = "redis.resources.limits.memory"
      value = "160Mi"
    },
  ]

  wait          = true
  wait_for_jobs = true
  timeout       = 900
}

resource "helm_release" "argocd_root" {
  name      = "argocd-root"
  chart     = "${path.module}/../../../helm/argocd-root"
  namespace = "argocd"

  # Argo CD CRD'leri kurulduktan sonra: Application bir CRD kaynagi.
  depends_on = [
    helm_release.argocd,
    helm_release.cluster_issuers,
    kubernetes_namespace_v1.ortamlar,
    kubernetes_secret_v1.db,
  ]
}
