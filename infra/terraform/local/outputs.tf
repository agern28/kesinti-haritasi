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
