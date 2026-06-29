# Local Kubernetes Deployment (Docker Desktop)

Run the full Shop stack on your local machine using Docker Desktop's built-in Kubernetes. Secrets are fetched automatically from AWS Secrets Manager by the External Secrets Operator — no credentials are stored in git or in any manifest file.

---

## Prerequisites

| Requirement | How to get it |
|---|---|
| Docker Desktop | Already installed — enable Kubernetes in Settings → Kubernetes → Enable Kubernetes |
| `helm` | `brew install helm` — required to install External Secrets Operator |
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

Run the deploy script from the `infra` repo:

```bash
# From the infra repo root
./scripts/local-deploy.sh
```

The script:
1. Switches kubectl to the `docker-desktop` context
2. Installs **External Secrets Operator** via Helm (skips if already installed)
3. Installs nginx Ingress controller (skips if already installed)
4. Applies shared infra manifests via `k8s/overlays/local/` — creates namespace, MongoDB, Redis, Zipkin, ESO `SecretStore`, and `ExternalSecret` resources
5. Creates the `aws-credentials` Kubernetes Secret from your local AWS CLI config — ESO uses this to authenticate against AWS Secrets Manager
6. Waits for ESO to sync all three secrets (`mongodb-credentials`, `mongodb-keyfile`, `shop-secret`) from AWS
7. Waits for `mongo-0` to be ready, then initialises the replica set
8. Starts a port-forward tunnel so the app is reachable at `localhost:9090`

Service deployments (shop, gateway, ping-service) are **not** applied by this script — each is deployed by its own Jenkins pipeline or manually:

```bash
# Deploy gateway (from gateway repo)
kubectl apply -f k8s/deployment.yaml
kubectl apply -f k8s/service.yaml
kubectl apply -f k8s/ingress.yaml

# Deploy shop (from shop repo)
kubectl apply -f k8s/configmap.yaml
kubectl apply -f k8s/service.yaml
kubectl apply -f k8s/deployment.yaml

# Deploy ping-service (from ping-service repo)
kubectl apply -f k8s/deployment.yaml
kubectl apply -f k8s/service.yaml
```

Total time: ~4–6 minutes (first run installs ESO and pulls images).

---

## How secrets work (External Secrets Operator)

Instead of fetching secrets manually with the AWS CLI, ESO acts as a bridge between AWS Secrets Manager and Kubernetes:

```
AWS Secrets Manager          ESO                        Kubernetes
─────────────────────        ───────────────────        ─────────────────
shop/mongo-user         ──▶  ExternalSecret        ──▶  mongodb-credentials
shop/mongo-password     ──▶  mongodb-credentials   ──▶  (username, password)
shop/mongodb-keyfile    ──▶  ExternalSecret        ──▶  mongodb-keyfile
                        ──▶  mongodb-keyfile        ──▶  (keyfile)
shop/jwt-secret         ──▶  ExternalSecret        ──▶  shop-secret
shop/admin-password     ──▶  shop-secret           ──▶  (JWT_SECRET,
shop/mongo-user         ──▶  (template)            ──▶   ADMIN_PASSWORD,
shop/mongo-password         assembles URI               SPRING_DATA_MONGODB_URI)
```

The `SPRING_DATA_MONGODB_URI` is assembled by ESO's template engine at sync time — the full URI is never written anywhere in plain text.

ESO re-syncs every **1 hour** automatically. If an AWS secret value changes, ESO updates the Kubernetes Secret, and the next `kubectl rollout restart` picks it up.

### Key resources

| Resource | Kind | File (in `infra` repo) | Purpose |
|---|---|---|---|
| `aws-secretsmanager` | `SecretStore` | `k8s/overlays/local/secret-store.yaml` | Tells ESO how to authenticate to AWS (static credentials from `aws-credentials` k8s Secret) |
| `mongodb-credentials` | `ExternalSecret` | `k8s/base/external-secrets/mongodb-credentials-es.yaml` | Syncs `username` + `password` from AWS |
| `mongodb-keyfile` | `ExternalSecret` | `k8s/base/external-secrets/mongodb-keyfile-es.yaml` | Syncs keyfile from AWS |
| `shop-secret` | `ExternalSecret` | `k8s/base/external-secrets/shop-secret-es.yaml` | Syncs JWT, admin password, assembles MongoDB URI |
| `aws-credentials` | `Secret` | created by deploy script | AWS access key ID + secret — never committed to git |

### Check sync status

```bash
# Are all ExternalSecrets synced?
kubectl get externalsecrets -n shop

# Expected output:
# NAME                  STORETYPE   STORE              STATUS        READY
# mongodb-credentials   SecretStore aws-secretsmanager SecretSynced  True
# mongodb-keyfile       SecretStore aws-secretsmanager SecretSynced  True
# shop-secret           SecretStore aws-secretsmanager SecretSynced  True

# Is the SecretStore able to reach AWS?
kubectl get secretstore -n shop

# Detailed sync error (if READY = False):
kubectl describe externalsecret mongodb-credentials -n shop
```

---

## Access the app

> **Why port-forward?** Docker Desktop on Mac runs Kubernetes inside a hidden Linux VM. `LoadBalancer` services never get an external IP assigned (they stay `<pending>`) and `NodePort` services are not reachable on `localhost` either — traffic to the host doesn't automatically reach inside the VM. `kubectl port-forward` creates an explicit tunnel from your Mac to the cluster, which is the correct local access method.

The deploy script starts the tunnel automatically. To start it manually after a reboot:

```bash
kubectl port-forward -n ingress-nginx svc/ingress-nginx-controller 9090:80 &
```

| Endpoint | URL |
|---|---|
| Swagger UI | http://localhost:9090/swagger-ui/index.html |
| Health check | http://localhost:9090/actuator/health |
| Login | `POST http://localhost:9090/auth/login` |
| Ping (GET) | `GET http://localhost:9090/ping` → `pong - GET` |
| Ping (POST) | `POST http://localhost:9090/ping` → `pong - POST` |
| Ping (PUT) | `PUT http://localhost:9090/ping` → `pong - PUT` |
| Ping (DELETE) | `DELETE http://localhost:9090/ping` → `pong - DELETE` |
| Zipkin traces | see [Zipkin](#zipkin) below |

The `/ping/**` path is **public** — no token required. This makes it easy to verify API gateway path-based routing without needing to log in first.

### Swagger quick start

1. Open `http://localhost:9090/swagger-ui/index.html`
2. Register: `POST /auth/register` — body: `{"username":"yourname","password":"Test1234!","email":"you@example.com"}`
3. Login: `POST /auth/login` — body: `{"username":"yourname","password":"Test1234!"}` — copy the `token`
4. Click **Authorize** (🔒 top right) → paste the token (no `Bearer` prefix needed — Swagger adds it)
5. All protected endpoints are now unlocked

> `/auth/**` and `/swagger-ui/**` are public — the **Spring Cloud Gateway** lets them through without a token. All other paths require a valid Bearer token; the gateway returns `401` before the request ever reaches the shop service.

### Zipkin

```bash
kubectl port-forward svc/zipkin 9411:9411 -n shop &
```

Then open `http://localhost:9411` — every API call appears as a distributed trace.

### Stop the tunnels

```bash
pkill -f "kubectl port-forward"
```

---

## How the local overlay differs from EKS

| Difference | EKS (`overlays/eks`) | Local (`overlays/local`) |
|---|---|---|
| StorageClass | `gp2-csi` (EBS CSI driver) | `standard` (Docker Desktop hostpath) |
| SecretStore auth | IRSA — IAM role via annotated service account | Static AWS credentials stored in `aws-credentials` k8s Secret |
| Access method | Load balancer hostname | `kubectl port-forward` to nginx ingress controller |

The `ExternalSecret` resources (base) are identical in both environments — only the `SecretStore` authentication method differs.

---

## Directory layout

Each repo contains only the manifests it owns:

```
infra/                                 # github.com/nelsonvillam/infra
├── k8s/
│   ├── base/                          # Shared resources for all environments
│   │   ├── kustomization.yaml
│   │   ├── namespace.yaml
│   │   ├── external-secrets/
│   │   │   ├── mongodb-credentials-es.yaml
│   │   │   ├── mongodb-keyfile-es.yaml
│   │   │   └── shop-secret-es.yaml
│   │   ├── mongodb/
│   │   ├── redis/
│   │   └── zipkin/
│   └── overlays/
│       ├── local/
│       │   ├── kustomization.yaml     # patches StorageClass → standard
│       │   └── secret-store.yaml      # SecretStore: static AWS credentials (Docker Desktop)
│       └── eks/
│           ├── kustomization.yaml
│           ├── storageclass.yaml      # gp2-csi StorageClass (EBS CSI driver)
│           └── secret-store.yaml      # SecretStore: IRSA (EKS)
├── scripts/
│   ├── local-deploy.sh
│   └── mongo-init.sh
└── docker-compose.yml

shop/                                  # github.com/nelsonvillam/shop
└── k8s/
    ├── deployment.yaml
    ├── service.yaml
    └── configmap.yaml

gateway/                               # github.com/nelsonvillam/gateway
└── k8s/
    ├── deployment.yaml
    ├── service.yaml
    └── ingress.yaml                   # nginx Ingress → gateway service

ping-service/                          # github.com/nelsonvillam/ping-service
└── k8s/
    ├── deployment.yaml
    └── service.yaml
```

---

## Useful commands

```bash
# List namespaces
kubectl get namespaces

# Watch all pods in the shop namespace
kubectl get pods -n shop --watch

# See pods across ALL namespaces
kubectl get pods --all-namespaces

# Tail gateway logs (JWT validation decisions, routing errors)
kubectl logs -n shop -l app=gateway -f

# Tail shop app logs
kubectl logs -n shop -l app=shop -f

# Tail ping-service logs
kubectl logs -n shop -l app=ping-service -f

# Tail last 100 lines without following
kubectl logs -n shop -l app=shop --tail=100

# Restart gateway (e.g. after JWT_SECRET rotation)
kubectl rollout restart deployment/gateway -n shop

# Check ESO sync status
kubectl get externalsecrets -n shop
kubectl get secretstore -n shop

# Force ESO to re-sync a secret immediately (e.g. after rotating in AWS)
kubectl annotate externalsecret mongodb-credentials \
  force-sync=$(date +%s) --overwrite -n shop

# Check MongoDB replica set status
kubectl exec -n shop mongo-0 -- \
  mongosh --quiet --eval "rs.status().members.forEach(m => print(m.name, m.stateStr))"

# Open a mongosh session
kubectl exec -n shop -it mongo-0 -- mongosh \
  -u <user> -p <password> --authenticationDatabase admin

# Restart shop pods (picks up new image or secret values after ESO sync)
kubectl rollout restart deployment/shop -n shop

# Restart the port-forward tunnel (e.g. after a reboot)
kubectl port-forward -n ingress-nginx svc/ingress-nginx-controller 9090:80 &

# Re-run RS init job (if replica set needs re-initialising) — from infra repo
kubectl delete job mongodb-rs-init -n shop --ignore-not-found=true
kubectl apply -f infra/k8s/base/mongodb/rs-init-job.yaml
```

---

## Teardown

```bash
# Stop port-forward tunnels
pkill -f "kubectl port-forward"

# Delete everything in the shop namespace (ESO-managed secrets are deleted with it)
kubectl delete namespace shop

# Remove nginx Ingress controller
kubectl delete -f \
  https://raw.githubusercontent.com/kubernetes/ingress-nginx/controller-v1.10.0/deploy/static/provider/cloud/deploy.yaml

# Remove External Secrets Operator
helm uninstall external-secrets -n external-secrets
kubectl delete namespace external-secrets

# Or disable Kubernetes entirely in Docker Desktop → Settings → Kubernetes
```

---

## Troubleshooting

| Problem | Cause | Fix |
|---|---|---|
| `kubectl get nodes` hangs | Wrong context | `kubectl config use-context docker-desktop` |
| App not reachable at `localhost:9090` | Port-forward tunnel not running | `kubectl port-forward -n ingress-nginx svc/ingress-nginx-controller 9090:80 &` |
| nginx LoadBalancer stuck at `<pending>` | Docker Desktop does not provision LB IPs | Expected — use port-forward instead (see above) |
| nginx namespace stuck in `Terminating` | LoadBalancer service has cloud finalizer | `kubectl patch svc ingress-nginx-controller -n ingress-nginx -p '{"metadata":{"finalizers":[]}}' --type=merge` |
| ExternalSecret READY = False | SecretStore not ready or AWS credentials invalid | `kubectl describe externalsecret <name> -n shop` — check Events section |
| SecretStore READY = False | `aws-credentials` secret missing or wrong keys | Re-run `./scripts/local-deploy.sh` — it recreates the secret from your AWS CLI config |
| ESO sync fails: `AccessDenied` | AWS credentials lack `secretsmanager:GetSecretValue` | Attach the required IAM policy to your AWS user/role |
| ESO sync fails: `ResourceNotFoundException` | AWS secret name mismatch | Verify secret names in AWS: `aws secretsmanager list-secrets --region sa-east-1` |
| Shop pods crashing | Secrets not yet synced by ESO | Wait for `kubectl get externalsecrets -n shop` to show `SecretSynced`; then restart pods |
| PVCs stuck in `Pending` | Wrong StorageClass | Ensure infra overlay is applied: `kubectl apply -k infra/k8s/overlays/local/` |
| Ingress returns 404 | nginx controller not running | `kubectl get pods -n ingress-nginx` — reinstall if missing |
| mongo-0 crash: `invalid char in key file` | keyfile synced incorrectly | Check `kubectl describe externalsecret mongodb-keyfile -n shop` |
| Image not found | Image never built/pushed | Run `./gradlew bootJar && docker build -t nelsonvillam/shop:latest .` |
| All requests return 401 | Gateway pod not ready or `JWT_SECRET` not synced | `kubectl get pods -n shop` — check gateway; verify `kubectl get externalsecrets -n shop` shows `SecretSynced` |
| Login returns 401 | Sending Authorization header on login | Remove the `Authorization` header — `/auth/login` is public and issues the token |
| Token expired | Tokens last 15 minutes | Re-run `POST /auth/login` to get a fresh token |
| Gateway pod in `CrashLoopBackOff` | `JWT_SECRET` env var missing | ESO hasn't synced `shop-secret` yet — wait for `SecretSynced` then restart gateway |
