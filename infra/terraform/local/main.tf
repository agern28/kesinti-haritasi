# Kume ustu kurulum: ortam namespace'leri, cert-manager, ClusterIssuer'lar.
# Uygulamalarin kendisi buradan kurulmuyor; onlar Faz 7'de Argo CD ile geliyor.

resource "kubernetes_namespace_v1" "ortamlar" {
  for_each = toset(var.namespaces)

  metadata {
    name = each.value
    labels = {
      "app.kubernetes.io/part-of" = "kesinti-haritasi"
      # Argo CD'nin yonettigi namespace'ler; Faz 7'de Application'lar bunlari hedefleyecek.
      "kesinti-haritasi/managed-by" = "terraform"
    }
  }
}

resource "helm_release" "cert_manager" {
  name             = "cert-manager"
  repository       = "https://charts.jetstack.io"
  chart            = "cert-manager"
  version          = var.cert_manager_version
  namespace        = "cert-manager"
  create_namespace = true

  # CRD'leri chart kuruyor: ClusterIssuer'lar hemen ardindan kurulabilsin.
  set = [
    {
      name  = "crds.enabled"
      value = "true"
    },
    {
      name  = "resources.requests.cpu"
      value = "10m"
    },
    {
      name  = "resources.requests.memory"
      value = "64Mi"
    },
    {
      name  = "resources.limits.memory"
      value = var.cert_manager_memory_limit
    },
    {
      name  = "webhook.resources.requests.memory"
      value = "32Mi"
    },
    {
      name  = "webhook.resources.limits.memory"
      value = var.cert_manager_memory_limit
    },
    {
      name  = "cainjector.resources.requests.memory"
      value = "64Mi"
    },
    {
      name  = "cainjector.resources.limits.memory"
      value = "192Mi"
    },
  ]

  # Webhook hazir olmadan issuer kurmaya kalkmasin.
  wait          = true
  wait_for_jobs = true
  timeout       = 600
}

resource "helm_release" "cluster_issuers" {
  name      = "cluster-issuers"
  chart     = "${path.module}/../../../helm/cluster-issuers"
  namespace = "cert-manager"

  # Lokalde kendi CA'miz; Let's Encrypt gercek alan adi isteyecegi icin kapali.
  set = [
    {
      name  = "ca.enabled"
      value = "true"
    },
    {
      name  = "letsencrypt.enabled"
      value = "false"
    },
  ]

  depends_on = [helm_release.cert_manager]
}
