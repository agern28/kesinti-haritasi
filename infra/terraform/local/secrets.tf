# PostgreSQL parolasi: Terraform uretiyor, repoya girmiyor.
# Terraform state'i (.gitignore'da) parolayi tutuyor; lokal kume icin kabul edilebilir.
# Bulutta bunun yerine harici bir secret deposu (ornegin Vault ya da SOPS) kullanilir.

resource "random_password" "postgres" {
  length = 32
  # Ozel karakter yok: JDBC URL'inde, psql komutlarinda ve shell'de kacis derdi olmasin.
  special = false
}

resource "kubernetes_secret_v1" "db" {
  # Ayni parola uc namespace'te: postgres'in kendisi (kesinti-data) ve iki ortamin api'si.
  for_each = toset(var.namespaces)

  metadata {
    name      = var.db_secret_name
    namespace = each.value
  }

  data = {
    "postgres-password" = random_password.postgres.result
  }

  type = "Opaque"

  depends_on = [kubernetes_namespace_v1.ortamlar]
}
