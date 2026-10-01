# Phase 6 - Infrastructure (local k3s)

The original Phase 6 was a Hetzner CX23 server, k3s via cloud-init and Let's Encrypt certificates. To keep the project runnable on my own machine, the cluster now runs locally instead: the same k3s, the same Traefik, but inside Docker and without spending anything. The Hetzner path is not closed; the end of this note lists the only work needed to move to the cloud.

Decision: see the 2026-10-02 row in the decision table in [phases.md](phases.md).

## The cluster

`infra/k3d/cluster.yaml`, in k3d's own config format (`k3d.io/v1alpha5`):

- 1 server + 1 agent. Two nodes so pod placement is visible; `agents: 0` works too if memory gets tight.
- `image: rancher/k3s:v1.35.5-k3s1` pinned, so deleting and recreating the cluster doesn't drift the version.
- 80 and 443 bound to localhost through the k3d load balancer, so `http://kesinti.localhost` opens straight from the browser, no `kubectl port-forward`.
- metrics-server is left enabled: the HPA in Phase 7 depends on it.
- `--tls-san=host.k3d.internal` added so certificate names hold for requests from inside the cluster back to the host.

Create and delete:

```bash
make cluster-up      # cluster + everything on top of it
make cluster-status  # nodes, pods, issuers
make cluster-down    # k3d cluster delete kesinti
```

`make cluster-down` removes the cluster completely and leaves the compose application data alone. If you delete and recreate the cluster, the Terraform state goes stale; the next `terraform apply` sees the resources are gone and recreates them, nothing has to be cleaned up by hand.

## Terraform

k3d creates the cluster, Terraform does everything on top of it: `infra/terraform/local`.

| Resource | What |
|---|---|
| `kubernetes_namespace_v1.ortamlar` | `kesinti-int` and `kesinti-prod`. Argo CD will target these in Phase 7 |
| `helm_release.cert_manager` | cert-manager v1.21.2 in its own namespace, with CRDs and memory limits |
| `helm_release.cluster_issuers` | The in-repo `helm/cluster-issuers` chart: the issuers |

Why the issuers are a separate Helm chart rather than `kubernetes_manifest`: `kubernetes_manifest` asks the API for the CRD schema at plan time. The ClusterIssuer CRD is installed by cert-manager within the same apply, so at plan time it doesn't exist yet and the plan fails. Helm does no such validation, so ordering with `depends_on` is enough.

cert-manager gets resource requests and limits (`128Mi`, `192Mi` for cainjector) so the 2 vCPU / 4 GB rule from CLAUDE.md holds locally too and the same values work if this moves to the cloud.

```bash
cd infra/terraform/local
terraform init
terraform plan     # 4 resources
terraform apply
```

`terraform output` gives the namespace list, the installed cert-manager version and the issuer name Ingresses should use (`kesinti-ca`).

## Certificates

Let's Encrypt does not work locally: HTTP-01 validation needs a real, externally reachable domain. So we issue our own CA instead. `helm/cluster-issuers` is three steps:

1. `selfsigned-bootstrap` (ClusterIssuer): a self-signing issuer.
2. `kesinti-ca` (Certificate, in the cert-manager namespace): `isCA: true`, valid for a year, ECDSA P-256. The result lands in the `kesinti-ca-tls` secret.
3. `kesinti-ca` (ClusterIssuer): signs with the CA in that secret. This is what Ingresses will use.

The chart has two modes: with `letsencrypt.enabled=true` it renders an ACME issuer (staging endpoint by default) and requires an email address. Moving to the cloud changes values, not the chart.

Verified (2026-10-01):

```
kubectl get clusterissuers
NAME                   READY   AGE
kesinti-ca             True    52s     # message: Signing CA verified
selfsigned-bootstrap   True    52s
```

I issued a throwaway Certificate for `int.kesinti.localhost`; it was ready in two seconds and its content was what it should be:

```
issuer=CN=Kesinti Haritasi Lokal CA
notBefore=Oct  1 21:00:48 2026 GMT
notAfter=Dec 30 21:00:48 2026 GMT
```

The test certificate was deleted afterwards; the real Ingress certificates arrive with the services in Phase 7. Browsers will warn because they don't know this CA, which is expected; the chain itself is real and can be shown with `openssl`.

## Addresses

`kesinti.localhost` (prod) and `int.kesinti.localhost` (int). Windows resolves these names to 127.0.0.1/::1 on its own, no hosts file entry was needed:

```
curl http://kesinti.localhost/   ->  404 (::1)
```

404 is the correct answer: Traefik is up, there is no Ingress yet.

## CI

The path filters of the four workflows from Phase 5 only watch `services/**`, `frontend/**` and `docker-compose.yml`, so the Terraform and Helm files written in this phase went through no run at all. A fifth workflow was added: `.github/workflows/infra.yml`, triggered by changes under `infra/**` and `helm/**`.

It has two jobs. `terraform`: `terraform fmt -check -recursive`, then `terraform init -backend=false` and `validate` in every directory that has a `.tf` file. It never connects to a cluster, so the runner needs no kube access. `helm`: `helm lint` for every chart, rendering `cluster-issuers` in both modes, and a check that the email requirement really fails the render when `letsencrypt.enabled=true` (if the render succeeds, the job fails). The last step verifies that `infra/k3d/cluster.yaml` is valid YAML.

Terraform and Helm ship with the GitHub runner image, so no extra actions were needed; the only action is `actions/checkout`, pinned by commit SHA like everywhere else.

## Resource use

The cluster and the compose stack together use 2.3 GB of RAM (out of the 7.6 GB WSL gets). kube-prometheus-stack in Phase 8 will be the heaviest addition, and it is planned with trimmed-down values anyway.

## Where I got stuck

- `sudo` asks for a password on this machine, so k3d went into `~/.local/bin` instead of `/usr/local/bin`. The Makefile adds that directory to `PATH` itself; in a login shell `~/.local/bin` is already on the PATH.
- The k3d v5.9.0 release does not publish a separate sha256 file next to the binary. The download came from the official GitHub release over HTTPS; the sha256 of what was downloaded is recorded here: `06d8f25bc3a971c4eb29e0ff08429b180402db0f4dec838c9eac427e296800a0` (24,887,458 bytes).
- I first pinned cert-manager at `v1.19.2`; `helm search repo` showed the current release is `v1.21.2`, so the default was updated.
- Traefik is installed by a Helm job inside k3s and that job retried three times while pulling images on first boot (`helm-install-traefik` RESTARTS 3). It completed in the end.
- Deleting the cluster and rebuilding it with `make cluster-up` (4 resources recreated in 70 seconds, with no trouble from the stale Terraform state) exposed one gap: right after the command finished, `curl http://localhost/` got nothing, because the Traefik install job was still running. The Makefile now has a `wait-traefik` step and `cluster-up` waits for the Traefik rollout; after that 80 and 443 answer 404 straight away.

## If this moves to the cloud

Everything written in this phase stays. What gets added:

1. `infra/terraform/hetzner`: hcloud provider, a CX23 server, firewall (22 only from your IP, 80/443 open), SSH key, k3s via cloud-init. Plus the step to fetch the kubeconfig locally.
2. `helm/cluster-issuers` values: `letsencrypt.enabled=true`, an email address, ACME staging first and then prod.
3. A real domain and a DNS A record.

Phases 7-9 (Helm charts, Argo CD, observability, k6) run the same way on the same cluster either way.
