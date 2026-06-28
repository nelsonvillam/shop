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
7. Starts a port-forward tunnel so the app is reachable at `localhost:9090`

Total time: ~3–5 minutes (first run pulls images).

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
| Zipkin traces | see [Zipkin](#zipkin) below |

### Swagger quick start

1. Open `http://localhost:9090/swagger-ui/index.html`
2. Register: `POST /auth/register` — body: `{"username":"yourname","password":"Test1234!","email":"you@example.com"}`
3. Login: `POST /auth/login` — body: `{"username":"yourname","password":"Test1234!"}` — copy the `token`
4. Click **Authorize** (🔒 top right) → paste `Bearer <token>`
5. All protected endpoints are now unlocked

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

The local deployment uses a Kustomize overlay at `k8s/overlays/local/` that makes two changes relative to the EKS base:

| Difference | EKS (`overlays/eks`) | Local (`overlays/local`) |
|---|---|---|
| StorageClass | `gp2-csi` (EBS CSI driver) | `standard` (Docker Desktop hostpath) |
| `storageclass.yaml` | Included — creates `gp2-csi` | Excluded — Docker Desktop already has `standard` |
| Access method | Load balancer hostname | `kubectl port-forward` to nginx ingress controller |

Everything else — service names, MongoDB URI, health probes, Ingress routing — is identical.

---

## Directory layout

```
k8s/
├── base/                      # Shared resources for all environments
│   ├── kustomization.yaml
│   ├── namespace.yaml
│   ├── mongodb/
│   ├── redis/
│   ├── zipkin/
│   └── shop/
├── overlays/
│   ├── local/                 # Docker Desktop — standard StorageClass
│   │   └── kustomization.yaml
│   └── eks/                   # EKS — gp2-csi StorageClass + storageclass.yaml
│       ├── kustomization.yaml
│       └── storageclass.yaml
└── kustomization.yaml         # Points to overlays/eks (used by CI/CD)
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

# Tail shop app logs
kubectl logs -n shop -l app=shop -f

# Tail last 100 lines without following
kubectl logs -n shop -l app=shop --tail=100

# Check MongoDB replica set status
kubectl exec -n shop mongo-0 -- \
  mongosh --quiet --eval "rs.status().members.forEach(m => print(m.name, m.stateStr))"

# Open a mongosh session
kubectl exec -n shop -it mongo-0 -- mongosh \
  -u <user> -p <password> --authenticationDatabase admin

# Restart shop pods (picks up new secret or image changes)
kubectl rollout restart deployment/shop -n shop

# Restart the port-forward tunnel (e.g. after a reboot)
kubectl port-forward -n ingress-nginx svc/ingress-nginx-controller 9090:80 &

# Re-run RS init job (if replica set needs re-initialising)
kubectl delete job mongodb-rs-init -n shop --ignore-not-found=true
kubectl apply -f k8s/base/mongodb/rs-init-job.yaml
```

---

## Teardown

```bash
# Stop port-forward tunnels
pkill -f "kubectl port-forward"

# Delete everything in the shop namespace
kubectl delete namespace shop

# Remove nginx Ingress controller
kubectl delete -f \
  https://raw.githubusercontent.com/kubernetes/ingress-nginx/controller-v1.10.0/deploy/static/provider/cloud/deploy.yaml

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
| PVCs stuck in `Pending` | Wrong StorageClass | Ensure overlay is applied: `kubectl apply -k k8s/overlays/local/` |
| Ingress returns 404 | nginx controller not running | `kubectl get pods -n ingress-nginx` — reinstall if missing |
| Secrets contain `CHANGE_ME` | Secret YAML files applied directly | Only use `./scripts/local-deploy.sh`, never `kubectl apply -k k8s/` (base) |
| mongo-0 crash: `invalid char in key file` | keyfile secret wrong | Re-run `./scripts/local-deploy.sh` — it upserts correct secrets |
| Shop pods crashing | Secrets not yet created | Wait for secret step; or run `kubectl rollout restart deployment/shop -n shop` |
| Image not found | Image never built/pushed | Run `./gradlew bootJar && docker build -t nelsonvillam/shop:latest .` |
| Login returns 401 | Sending Authorization header on login | Remove the `Authorization` header — `/auth/login` is public and issues the token |
| Token expired | Tokens last 15 minutes | Re-run `POST /auth/login` to get a fresh token |
