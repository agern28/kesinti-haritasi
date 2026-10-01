terraform {
  required_version = ">= 1.6"

  required_providers {
    kubernetes = {
      source  = "hashicorp/kubernetes"
      version = "~> 2.38"
    }
    helm = {
      source  = "hashicorp/helm"
      version = "~> 3.0"
    }
  }
}

# Kume k3d ile kuruluyor (infra/k3d/cluster.yaml), Terraform kumenin ustunu kuruyor.
# Hetzner'e tasinirsa buraya hcloud provider'i ve sunucu kaynaklari eklenir; asagidaki
# namespace / cert-manager / issuer kismi aynen kalir.
provider "kubernetes" {
  config_path    = var.kubeconfig_path
  config_context = var.kube_context
}

provider "helm" {
  kubernetes = {
    config_path    = var.kubeconfig_path
    config_context = var.kube_context
  }
}
