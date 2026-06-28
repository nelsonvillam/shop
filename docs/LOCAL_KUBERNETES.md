# Local Kubernetes Deployment (Docker Desktop)

Run the full Shop stack on your local machine using Docker Desktop's built-in Kubernetes. Secrets are still fetched from AWS Secrets Manager — no credentials are stored locally.

---

## Prerequisites

| Requirement | How to get it |
|---|---|
| Docker Desktop | Already installed — enable Kubernetes in Settings → Kubernetes → Enable Kubernetes |
| AWS CLI configured | `aws sts get-caller-identity` should return your identity |
| AWS credentials | Must have `secretsmanager:GetSecretValue` on `shop/*` |
| Built Docker image | Run the Jenkins pipeline at least once, or build locally (see below) |

### Enable Kubernetes in Docker Desktop

Open Docker Desktop → Settings → Kubernetes → check **Enable Kubernetes** → Apply & Restart. Takes ~2 minutes.

Verify:

```bash
kubectl config use-context docker-desktop
kubectl get nodes
# NAME                    STATUS   ROLES           AGE
# desktop-control-plane   Ready    control-plane   ...
```

### Build the image locally (if no CI pipeline run yet)

```bash
./gradlew bootJar
docker build -t nelsonvillam/shop:latest .
```

---

## Deploy

Run the deploy script from the repo root:

```bash
./scripts/local-deploy.sh
```

The script:
1. Switches kubectl to the `docker-desktop` context
2. Installs nginx Ingress controller (skips if already installed)
3. Applies all Kubernetes manifests via `k8s/overlays/local/`
4. Fetches secrets from AWS Secrets Manager and creates them in the cluster
5. Waits for `mongo-0` to be ready, then initialises the replica set
6. Waits for the shop deployment to roll out

Total time: ~3–5 minutes (first run pulls images).

---

## Access the app

| Endpoint | URL |
|---|---|
| Swagger UI | http://localhost/swagger-ui/index.html |
| Health check | http://localhost/actuator/health |
| Zipkin traces | `kubectl port-forward svc/zipkin 9411:9411 -n shop` then http://localhost:9411 |

---

## How the local overlay differs from EKS

The local deployment uses a Kustomize overlay at `k8s/overlays/local/` that makes two changes relative to the EKS base:

| Difference | EKS base | Local overlay |
|---|---|---|
| StorageClass | `gp2-csi` (EBS CSI driver) | `standard` (Docker Desktop hostpath) |
| `storageclass.yaml` | Included (creates `gp2-csi`) | Excluded (Docker Desktop already has `standard`) |

Everything else — service names, MongoDB URI, health probes, Ingress — is identical.

---

## Directory layout

```
k8s/
├── kustomization.yaml         # EKS base (used by CI/CD pipeline)
├── storageclass.yaml          # EKS only — gp2-csi via EBS CSI driver
├── mongodb/ redis/ zipkin/ shop/
└── overlays/
    └── local/
        └── kustomization.yaml # Local override — uses "standard" StorageClass
```

---

## Useful commands

```bash
# Watch all pods in the shop namespace
kubectl get pods -n shop --watch

# Tail shop app logs
kubectl logs -n shop -l app=shop -f

# Check MongoDB replica set status
kubectl exec -n shop mongo-0 -- \
  mongosh --quiet --eval "rs.status().members.forEach(m => print(m.name, m.stateStr))"

# Open a mongosh session
kubectl exec -n shop -it mongo-0 -- mongosh \
  -u <user> -p <password> --authenticationDatabase admin

# Restart shop pods (picks up new secret or image changes)
kubectl rollout restart deployment/shop -n shop

# Re-run RS init job (if replica set needs re-initialising)
kubectl delete job mongodb-rs-init -n shop --ignore-not-found=true
kubectl apply -f k8s/mongodb/rs-init-job.yaml
```

---

## Teardown

```bash
# Delete everything in the shop namespace
kubectl delete namespace shop

# Remove nginx Ingress controller
kubectl delete -f \
  https://raw.githubusercontent.com/kubernetes/ingress-nginx/controller-v1.10.0/deploy/static/provider/cloud/deploy.yaml

# Or disable Kubernetes entirely in Docker Desktop Settings
```

---

## Troubleshooting

| Problem | Cause | Fix |
|---|---|---|
| `kubectl get nodes` hangs | Wrong context | `kubectl config use-context docker-desktop` |
| PVCs stuck in `Pending` | Wrong StorageClass | Ensure overlay is applied: `kubectl apply -k k8s/overlays/local/` |
| Ingress returns 404 | nginx not installed | Script installs it automatically; check: `kubectl get pods -n ingress-nginx` |
| `localhost` not resolving | nginx LoadBalancer not ready | `kubectl get svc -n ingress-nginx` — EXTERNAL-IP should be `localhost` |
| Secrets contain `CHANGE_ME` | Secret YAML files applied directly | Only use `./scripts/local-deploy.sh`, never `kubectl apply -k k8s/` (base) |
| mongo-0 crash: `invalid char in key file` | keyfile secret wrong | Re-run `./scripts/local-deploy.sh` — it upserts correct secrets |
| Shop pods crashing | Secrets not yet created | Wait for secret step; or run `kubectl rollout restart deployment/shop -n shop` |
| Image not found | Image never built/pushed | Run `./gradlew bootJar && docker build -t nelsonvillam/shop:latest .` |
