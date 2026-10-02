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

output "izleme" {
  description = "Grafana, Prometheus ve Alertmanager'a nasil ulasilir"
  value = {
    grafana_url       = "https://${var.grafana_host}"
    grafana_kullanici = "admin"
    grafana_parola    = "kubectl -n monitoring get secret monitoring-grafana -o go-template='{{index .data \"admin-password\" | base64decode}}'"
    prometheus        = "kubectl -n monitoring port-forward svc/monitoring-kube-prometheus-prometheus 9090:9090"
    alertmanager      = "kubectl -n monitoring port-forward svc/monitoring-kube-prometheus-alertmanager 9093:9093"
    # Hassas degiskene bakmiyoruz: Terraform boyle bir cikti icin sensitive isaretlemek istiyor.
    telegram = "terraform.tfvars'ta telegram_bot_token ve telegram_chat_id doluysa Telegram alicisi kurulur"
  }
}

output "ortam_adresleri" {
  description = "Ingress adresleri"
  value = {
    int  = "https://int.kesinti.localhost"
    prod = "https://kesinti.localhost"
  }
}
