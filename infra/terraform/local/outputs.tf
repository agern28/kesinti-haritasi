output "namespaces" {
  description = "Olusturulan ortam namespace'leri"
  value       = [for ns in kubernetes_namespace_v1.ortamlar : ns.metadata[0].name]
}

output "cert_manager_version" {
  description = "Kurulu cert-manager chart surumu"
  value       = helm_release.cert_manager.version
}

output "cluster_issuer" {
  description = "Ingress'lerin kullanacagi ClusterIssuer adi"
  value       = "kesinti-ca"
}

output "argocd" {
  description = "Argo CD arayuzu ve ilk parolanin nasil okunacagi"
  value = {
    url           = "https://argocd.localhost"
    kullanici     = "admin"
    parola_komutu = "kubectl -n argocd get secret argocd-initial-admin-secret -o go-template='{{index .data \"password\" | base64decode}}'"
    chart_surumu  = helm_release.argocd.version
  }
}

output "ortam_adresleri" {
  description = "Ingress adresleri"
  value = {
    int  = "https://int.kesinti.localhost"
    prod = "https://kesinti.localhost"
  }
}
