# Kubernetes Deployment Guide

This document explains how the Shop API is deployed to Kubernetes: what each manifest does, how the components find each other, how MongoDB achieves high availability, and how CI/CD drives the rollout.

---

## Why Kubernetes

Docker Compose runs all containers on a single machine. If that machine goes down, the app goes down with it. Kubernetes distributes workloads across a cluster of nodes and adds:

| Capability | What it means in practice |
|---|---|
| Self-healing | Crashed pods are replaced automatically |
| Rolling deploys | New version rolls out without downtime |
| Horizontal scaling | Add replicas with one command |
| Health-gated traffic | Requests only reach pods that passed readiness checks |
| Secret management | Credentials stored in the cluster, not in env files |
| Service discovery | Pods find each other by name, not by IP |

---

## Architecture

```
╔══════════════════════════════════════════════════════════════════════════════════╗
║                        LOCAL (Docker Desktop)                                  ║
╚══════════════════════════════════════════════════════════════════════════════════╝

  Browser / curl / Postman
        │
        │  HTTP  localhost:9090
        │
        ▼
┌───────────────────────┐
│   kubectl port-forward │  ← TCP tunnel (Docker Desktop has no real LB)
│   localhost:9090→ :80  │
└───────────┬───────────┘
            │
            ▼
╔═══════════════════════════════════════════════════════════════════════════════╗
║  Kubernetes cluster (docker-desktop)                      shop namespace     ║
║                                                                               ║
║  ┌──────────────────────────────────────────────────────┐                    ║
║  │  ingress-nginx namespace                             │                    ║
║  │                                                      │                    ║
║  │  ┌────────────────────────────────────────────────┐  │                    ║
║  │  │  nginx Ingress Controller          (port 80)  │  │                    ║
║  │  │  NO host filter — accepts any hostname         │  │                    ║
║  │  │  path: /  →  gateway Service : 80              │  │                    ║
║  │  │  ✓ Path-based routing                          │  │                    ║
║  │  │  ✗ No TLS termination (HTTP only, local)       │  │                    ║
║  │  └────────────────┬───────────────────────────────┘  │                    ║
║  └───────────────────│──────────────────────────────────┘                    ║
║                      │                                                        ║
║         ┌────────────▼───────────┐                                            ║
║         │  gateway Service       │  ClusterIP :80                            ║
║         └────────────┬───────────┘                                            ║
║                      │                                                        ║
║         ┌────────────▼───────────────────────────┐                           ║
║         │  Spring Cloud Gateway pod   :8080      │                           ║
║         │                                        │                           ║
║         │  GlobalFilter: JwtAuthenticationFilter │                           ║
║         │                                        │                           ║
║         │  PUBLIC (no token required):           │                           ║
║         │    /auth/**   /actuator/**             │                           ║
║         │    /swagger-ui/**  /v3/api-docs/**     │                           ║
║         │                                        │                           ║
║         │  PROTECTED (Bearer token required):    │                           ║
║         │    validates JWT → adds X-User-Name    │                           ║
║         │    header → forwards to shop           │                           ║
║         │    invalid/missing token → 401         │                           ║
║         └────────────┬───────────────────────────┘                           ║
║                      │  routes /**  →  http://shop:80                        ║
║              ┌───────▼────────┐                                               ║
║              │  shop Service  │  ClusterIP :80                               ║
║              │  (round-robin) │                                               ║
║              └───────┬────────┘                                               ║
║                      │                                                        ║
║            ┌─────────┴──────────┐                                             ║
║            ▼                    ▼                                             ║
║   ┌─────────────────┐  ┌─────────────────┐                                   ║
║   │  shop pod 1     │  │  shop pod 2     │  Spring Boot :8080                ║
║   │  /actuator/     │  │  /actuator/     │  ← liveness probe  (kubelet)      ║
║   │   health/live   │  │   health/live   │                                    ║
║   │  /actuator/     │  │  /actuator/     │  ← readiness probe (kubelet)      ║
║   │   health/ready  │  │   health/ready  │                                    ║
║   └────┬──────┬─────┘  └────┬──────┬────┘                                    ║
║        │      └──────────────┘      │                                         ║
║        │             │              │                                         ║
║   ┌────▼────┐   ┌────▼──────────────▼────┐   ┌──────────┐                   ║
║   │  redis  │   │  mongo Service         │   │  zipkin  │                   ║
║   │ :6379   │   │  (headless per-pod DNS)│   │  :9411   │                   ║
║   └─────────┘   └────────────┬───────────┘   └──────────┘                   ║
║                ┌──────────────┼──────────────┐                               ║
║                ▼              ▼              ▼                               ║
║          ┌──────────┐  ┌──────────┐  ┌──────────┐                           ║
║          │ mongo-0  │  │ mongo-1  │  │ mongo-2  │  MongoDB :27017            ║
║          │ PRIMARY  │  │SECONDARY │  │SECONDARY │                            ║
║          │  10 Gi   │  │  10 Gi   │  │  10 Gi   │  PersistentVolumes        ║
║          └──────────┘  └──────────┘  └──────────┘                           ║
╚═══════════════════════════════════════════════════════════════════════════════╝

                                          ▲
                                          │  secretsmanager:GetSecretValue
                                          │
                              ┌───────────┴──────────┐
                              │  AWS Secrets Manager  │  sa-east-1
                              │  shop/mongo-user      │
                              │  shop/mongo-password  │
                              │  shop/admin-password  │
                              │  shop/jwt-secret      │
                              │  shop/mongodb-keyfile │
                              └───────────────────────┘
                                          ▲
                                          │  syncs every 1h
                              ┌───────────┴──────────┐
                              │  External Secrets     │
                              │  Operator (ESO)       │
                              │  namespace: external- │
                              │            secrets    │
                              └───────────────────────┘
```

| Layer | What's there | Notes |
|---|---|---|
| Entry | `kubectl port-forward` (local) / Load Balancer (EKS) | Docker Desktop has no real LB |
| Reverse proxy | nginx Ingress Controller | Path routing, no TLS locally |
| **API Gateway** | **Spring Cloud Gateway** | **JWT validation, X-User-Name header forwarding** |
| App tier | 2 shop pods, round-robin via ClusterIP Service | |
| DB tier | MongoDB 3-node replica set (1 PRIMARY, 2 SECONDARY) | |
| Secret sync | ESO → AWS Secrets Manager | Refreshes every 1h |

---

## Directory Structure

```
gateway/                            # Spring Cloud Gateway (separate Spring Boot project)
├── src/main/java/com/example/gateway/
│   ├── GatewayApplication.java
│   └── filter/
│       └── JwtAuthenticationFilter.java  # GlobalFilter: validates JWT, adds X-User-Name
├── src/main/resources/application.yml    # route: /** → http://shop:80
├── build.gradle
└── Dockerfile

k8s/
├── kustomization.yaml              # delegates to overlays/eks (used by CI/CD)
├── base/                           # shared resources for all environments
│   ├── kustomization.yaml
│   ├── namespace.yaml
│   ├── external-secrets/
│   │   ├── mongodb-credentials-es.yaml  # ExternalSecret — syncs username + password
│   │   ├── mongodb-keyfile-es.yaml      # ExternalSecret — syncs keyfile
│   │   └── shop-secret-es.yaml          # ExternalSecret — syncs JWT, admin pw, assembles URI
│   ├── gateway/
│   │   ├── deployment.yaml         # 1 replica, reads JWT_SECRET from shop-secret
│   │   └── service.yaml            # ClusterIP port 80 → 8080
│   ├── mongodb/
│   │   ├── statefulset.yaml        # 3-node replica set with keyFile auth
│   │   ├── headless-service.yaml   # stable DNS names for each pod
│   │   ├── service.yaml            # ClusterIP for tooling / ad-hoc access
│   │   └── rs-init-job.yaml        # Job that calls rs.initiate() once
│   ├── redis/
│   ├── zipkin/
│   └── shop/
│       ├── configmap.yaml          # non-sensitive env vars (REDIS_HOST, ZIPKIN_URL)
│       ├── deployment.yaml         # 2 replicas, liveness + readiness probes
│       ├── service.yaml            # ClusterIP port 80 → 8080
│       └── ingress.yaml            # nginx Ingress → gateway (not shop directly)
└── overlays/
    ├── local/                      # Docker Desktop
    │   ├── kustomization.yaml
    │   └── secret-store.yaml       # SecretStore: static AWS credentials
    └── eks/                        # EKS
        ├── kustomization.yaml
        ├── storageclass.yaml       # gp2-csi StorageClass (EBS CSI driver)
        └── secret-store.yaml       # SecretStore: IRSA (IAM role via service account)
```

No placeholder secret YAML files are committed to git. Secrets are managed exclusively by the External Secrets Operator — see [External Secrets Operator](#external-secrets-operator) below.

---

## Prerequisites

| Requirement | Notes |
|---|---|
| Kubernetes cluster | EKS, GKE, AKS, or local via `minikube` / `kind` |
| `kubectl` ≥ 1.14 | Built-in Kustomize support included |
| nginx Ingress Controller | `kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/controller-v1.10.0/deploy/static/provider/aws/deploy.yaml` |
| EBS CSI Driver (EKS only) | Required for PVC provisioning on K8s 1.27+ — see [StorageClass](#storageclass) |
| Docker Hub image pushed | Jenkins pipeline must have run at least once |
| `kubectl` configured | `~/.kube/config` pointed at the target cluster |

---

## External Secrets Operator

Secrets are managed by the **External Secrets Operator (ESO)**, which watches `ExternalSecret` resources and automatically syncs values from AWS Secrets Manager into Kubernetes `Secret` objects. No secret values are ever stored in git or in any manifest file.

### How it works

```
AWS Secrets Manager                ESO                        Kubernetes Secrets
───────────────────────            ─────────────────────      ──────────────────────
shop/mongo-user          ──────▶  ExternalSecret          ──▶  mongodb-credentials
shop/mongo-password      ──────▶  mongodb-credentials     ──▶  (username, password)

shop/mongodb-keyfile     ──────▶  ExternalSecret          ──▶  mongodb-keyfile
                                  mongodb-keyfile          ──▶  (keyfile)

shop/jwt-secret          ──────▶  ExternalSecret          ──▶  shop-secret
shop/admin-password      ──────▶  shop-secret             ──▶  (JWT_SECRET,
shop/mongo-user          ──────▶  (template engine)       ──▶   ADMIN_PASSWORD,
shop/mongo-password                assembles URI                 SPRING_DATA_MONGODB_URI)
```

The `SPRING_DATA_MONGODB_URI` is assembled at sync time by ESO's Go template engine — the full URI is never written in any file or log.

### SecretStore — authentication to AWS

A `SecretStore` tells ESO how to authenticate when calling AWS Secrets Manager. The auth method differs by environment:

| Environment | Auth method | How credentials are provided |
|---|---|---|
| Local (Docker Desktop) | Static credentials | `aws-credentials` k8s Secret, created by deploy script from `~/.aws/credentials` — never committed to git |
| EKS | IRSA | IAM role annotated on a service account; no static credentials needed |

### Sync interval and rotation

ESO re-syncs every **1 hour** by default. After rotating a secret in AWS, force an immediate re-sync:

```bash
kubectl annotate externalsecret mongodb-credentials \
  force-sync=$(date +%s) --overwrite -n shop
```

After re-sync, restart the affected pods to pick up the new values:

```bash
kubectl rollout restart deployment/shop -n shop
```

### Checking sync status

```bash
kubectl get externalsecrets -n shop
# NAME                  STATUS        READY
# mongodb-credentials   SecretSynced  True
# mongodb-keyfile       SecretSynced  True
# shop-secret           SecretSynced  True

kubectl get secretstore -n shop
# NAME                 STATUS  CAPABILITIES  READY
# aws-secretsmanager   Valid   ReadWrite     True

# Detailed error if READY = False:
kubectl describe externalsecret shop-secret -n shop
```

---

## One-Time Cluster Setup

### Step 1 — Install External Secrets Operator

```bash
helm repo add external-secrets https://charts.external-secrets.io
helm repo update
helm install external-secrets external-secrets/external-secrets \
  -n external-secrets --create-namespace \
  --set installCRDs=true \
  --wait
```

### Step 2 — Apply all resources

For local (Docker Desktop):
```bash
kubectl apply -k k8s/overlays/local/
```

For EKS:
```bash
kubectl apply -k k8s/overlays/eks/
```

This creates the namespace, all workload resources, and the `ExternalSecret` + `SecretStore` resources.

### Step 3 — Create the aws-credentials Secret (local only)

The local `SecretStore` uses static AWS credentials stored in a Kubernetes Secret. The deploy script creates this automatically, but you can also create it manually:

```bash
# Never commit this — create it imperatively only
kubectl create secret generic aws-credentials \
  --namespace shop \
  --from-literal=access-key-id="$(aws configure get aws_access_key_id)" \
  --from-literal=secret-access-key="$(aws configure get aws_secret_access_key)" \
  --from-literal=session-token="$(aws configure get aws_session_token || echo '')"
```

For EKS with IRSA, no static credentials are needed — skip this step.

### Step 4 — Wait for ESO to sync secrets

```bash
kubectl wait externalsecret/mongodb-credentials \
  --namespace shop --for=condition=Ready --timeout=60s
kubectl wait externalsecret/mongodb-keyfile \
  --namespace shop --for=condition=Ready --timeout=60s
kubectl wait externalsecret/shop-secret \
  --namespace shop --for=condition=Ready --timeout=60s
```

### Step 5 — Wait for mongo-0, then init the replica set

```bash
kubectl wait --for=condition=ready pod/mongo-0 --namespace shop --timeout=120s

kubectl apply -f k8s/base/mongodb/rs-init-job.yaml

kubectl logs -l job-name=mongodb-rs-init --namespace shop --follow
```

Expected output: `Replica set initialized successfully` or `Replica set already initialized`.

> **If the RS init Job started before secrets were synced:** The Job pod captures env vars at pod creation time. Delete the Job and reapply to create a fresh pod once ESO has finished syncing.
> ```bash
> kubectl delete job mongodb-rs-init --namespace shop
> kubectl apply -f k8s/base/mongodb/rs-init-job.yaml
> ```

---

## Deploy

### First deploy

```bash
# Apply overlay (local or eks)
kubectl apply -k k8s/overlays/local/

# Wait for ESO to sync secrets, then init MongoDB
kubectl wait externalsecret/mongodb-credentials --namespace shop --for=condition=Ready --timeout=60s
kubectl wait --for=condition=ready pod/mongo-0 --namespace shop --timeout=120s
kubectl apply -f k8s/base/mongodb/rs-init-job.yaml
```

### Subsequent deploys

The Jenkins pipeline handles this automatically. To deploy manually:

```bash
IMAGE_TAG=42   # replace with your build number
sed "s|nelsonvillam/shop:latest|nelsonvillam/shop:${IMAGE_TAG}|g" \
  k8s/base/shop/deployment.yaml | kubectl apply -f -

kubectl apply -f k8s/base/shop/configmap.yaml
kubectl apply -f k8s/base/shop/service.yaml
kubectl apply -f k8s/base/shop/ingress.yaml
kubectl rollout status deployment/shop --namespace shop --timeout=5m
```

---

## Components

### StorageClass

**File:** `k8s/storageclass.yaml`

```yaml
provisioner: ebs.csi.aws.com
volumeBindingMode: WaitForFirstConsumer
```

On EKS with Kubernetes 1.27 or later, the legacy in-tree `kubernetes.io/aws-ebs` provisioner is removed. PVCs will stay `Pending` indefinitely if you use a StorageClass that references it. The `gp2-csi` StorageClass uses the `ebs.csi.aws.com` provisioner from the `aws-ebs-csi-driver` EKS addon.

**Installing the EBS CSI driver addon (one-time, cluster-level):**

```bash
# Enable OIDC (required for the addon's IAM role)
eksctl utils associate-iam-oidc-provider \
  --cluster shop-cluster --region sa-east-1 --approve

# Create the IAM service account with the required policy
eksctl create iamserviceaccount \
  --name ebs-csi-controller-sa \
  --namespace kube-system \
  --cluster shop-cluster \
  --region sa-east-1 \
  --attach-policy-arn arn:aws:iam::aws:policy/service-role/AmazonEBSCSIDriverPolicy \
  --approve \
  --role-name AmazonEKS_EBS_CSI_DriverRole

# Install the addon
aws eks create-addon \
  --cluster-name shop-cluster \
  --addon-name aws-ebs-csi-driver \
  --region sa-east-1 \
  --service-account-role-arn $(aws iam get-role \
      --role-name AmazonEKS_EBS_CSI_DriverRole \
      --query Role.Arn --output text)
```

`volumeBindingMode: WaitForFirstConsumer` delays EBS volume creation until a pod is scheduled on a specific node, ensuring the volume is created in the same Availability Zone as the node.

---

### MongoDB StatefulSet

**File:** `k8s/mongodb/statefulset.yaml`

MongoDB runs as a `StatefulSet` with 3 replicas. StatefulSets differ from regular Deployments in two important ways:

| Feature | Deployment | StatefulSet |
|---|---|---|
| Pod names | Random suffix (`shop-abc12`) | Ordered index (`mongo-0`, `mongo-1`, `mongo-2`) |
| Pod DNS | Not stable | Stable: `<pod>.<service>.<namespace>.svc.cluster.local` |
| Storage | Shared or ephemeral | Each pod gets its own PersistentVolumeClaim |
| Startup order | Parallel | Sequential (mongo-0 → mongo-1 → mongo-2) |

Stable names and individual storage are both required for a MongoDB replica set — members must be addressable at predictable DNS names across restarts.

#### Init container — keyfile permissions

The MongoDB keyfile Secret is mounted read-only with permissions `0644` owned by `root`. MongoDB rejects keyfiles that are not `0400` and not owned by the `mongodb` user (`uid 999`). An init container fixes this before the main container starts:

```
[init container: busybox]
  1. Copy keyfile from Secret mount → emptyDir volume
  2. chmod 400
  3. chown 999:999

[main container: mongo:7]
  Reads keyfile from emptyDir — correct permissions ✓
```

#### Starting mongod

The StatefulSet uses `args` (not `command`) so the Docker image's entrypoint (`docker-entrypoint.sh`) still runs. This is important: the entrypoint creates the root user from `MONGO_INITDB_ROOT_USERNAME` / `MONGO_INITDB_ROOT_PASSWORD` on first start of each pod, before starting the real mongod process.

```yaml
args:
  - mongod
  - --replSet=rs0
  - --keyFile=/etc/mongodb/keyfile
  - --bind_ip_all
```

#### Persistent storage

Each pod gets a 10 Gi `PersistentVolumeClaim` with `storageClassName: gp2-csi`. The EBS CSI driver provisions an EBS volume automatically when the pod is scheduled.

> **StatefulSet VolumeClaimTemplate immutability:** `volumeClaimTemplates` cannot be edited in-place. To change `storageClassName` on an existing StatefulSet, delete it with `--cascade=orphan` (keeps PVCs and their data intact), then reapply:
> ```bash
> kubectl delete statefulset mongo --namespace shop --cascade=orphan
> kubectl apply -f k8s/mongodb/statefulset.yaml
> ```

---

### Headless Service

**File:** `k8s/mongodb/headless-service.yaml`

```yaml
spec:
  clusterIP: None
```

Setting `clusterIP: None` makes this a **headless service**. Instead of a virtual IP, Kubernetes DNS returns the individual pod IPs. This gives each pod a stable DNS name:

```
mongo-0.mongo-headless.shop.svc.cluster.local
mongo-1.mongo-headless.shop.svc.cluster.local
mongo-2.mongo-headless.shop.svc.cluster.local
```

These names are what the StatefulSet uses for `serviceName`, and what the RS init Job and the shop app's MongoDB URI reference.

---

### Replica Set Init Job

**File:** `k8s/mongodb/rs-init-job.yaml`

A Kubernetes `Job` that runs once and calls `rs.initiate()` to bootstrap the replica set. It:

1. Waits for `mongo-0` to accept authenticated connections
2. Calls `rs.status()` — if the RS is already initialized, prints a message and exits cleanly
3. Otherwise calls `rs.initiate()` with all 3 members

The Job is **idempotent** — safe to apply again if it fails or if you want to verify the state.

```bash
# Check Job status
kubectl get job mongodb-rs-init --namespace shop

# Check Job logs
kubectl logs -l job-name=mongodb-rs-init --namespace shop
```

> **Job pods capture env vars at creation time.** If the job pod started while `mongodb-credentials` contained wrong values, it will keep failing even after the secret is fixed. Delete the Job and reapply to create a fresh pod:
> ```bash
> kubectl delete job mongodb-rs-init --namespace shop
> kubectl apply -f k8s/mongodb/rs-init-job.yaml
> ```

After the Job completes:
- `mongo-0` becomes primary
- `mongo-1` and `mongo-2` sync from the primary and become secondaries
- The shop app can now connect using the full replica set URI

---

### Redis

**File:** `k8s/redis/deployment.yaml`

A single-replica `Deployment` running `redis:7-alpine`. Redis is used for application-level caching (managed by Spring Cache + `@Cacheable`). A regular `Deployment` is used instead of a `StatefulSet` because Redis is treated as a disposable cache — on restart, cache entries are rebuilt from MongoDB on the next request.

The `redis` `Service` resolves to this pod. The shop app sets:
```
REDIS_HOST=redis
REDIS_PORT=6379
```

Kubernetes DNS resolves `redis` → `redis.shop.svc.cluster.local` automatically within the namespace.

---

### Zipkin

**File:** `k8s/zipkin/deployment.yaml`

A single-replica `Deployment` running `openzipkin/zipkin:3`. The shop app ships distributed traces to:
```
ZIPKIN_URL=http://zipkin:9411
```

Zipkin is stateless — traces are stored in memory and lost on pod restart. For a production setup, configure Zipkin to use Elasticsearch or Cassandra as a backend storage.

---

### Spring Cloud Gateway

**Files:** `gateway/`, `k8s/base/gateway/`

Spring Cloud Gateway is a reactive Spring Boot application that acts as the single entry point for all external traffic. It runs in the cluster as its own pod and routes every request to the shop service after optionally validating a JWT.

#### Request flow

```
Client request
    │
    ▼
JwtAuthenticationFilter (GlobalFilter, order = -1)
    │
    ├─ path is public? (/auth/**, /actuator/**, /swagger-ui/**, /v3/api-docs/**)
    │       └─ forward as-is → shop
    │
    └─ protected path
            │
            ├─ Authorization: Bearer <token> present?
            │       NO  → 401 immediately (shop never receives the request)
            │
            └─ YES → validate token signature + expiry
                        │
                        ├─ invalid → 401
                        │
                        └─ valid  → add header X-User-Name: <subject>
                                        → forward to shop
```

#### JWT validation

The gateway uses the same key derivation as the shop service (`Keys.hmacShaKeyFor(secret.getBytes(UTF_8))`) so both can validate tokens issued by `/auth/login`. The `JWT_SECRET` value is injected from the existing `shop-secret` Kubernetes Secret — no separate secret is needed.

#### Why a gateway instead of relying on shop's own security?

| Without gateway | With gateway |
|---|---|
| Every request hits shop, even invalid ones | Invalid tokens rejected at the gateway — shop never wakes up |
| Scaling shop means scaling auth too | Auth logic lives in one place; shop focuses on business logic |
| Hard to add rate limiting, tracing headers, or A/B routing | Gateway is the single extension point for cross-cutting concerns |

#### Public vs protected paths

| Pattern | Auth required | Reason |
|---|---|---|
| `/auth/**` | No | Login and register endpoints — they issue the token |
| `/actuator/**` | No | Health probes hit by kubelet from inside the cluster |
| `/swagger-ui/**`, `/v3/api-docs/**` | No | Swagger UI must load before the user has a token |
| Everything else | Yes | All business API endpoints |

#### Routing

All traffic is forwarded to `http://shop:80` (Kubernetes DNS resolves `shop` to the shop ClusterIP Service within the namespace). There is one catch-all route:

```yaml
routes:
  - id: shop
    uri: http://shop:80
    predicates:
      - Path=/**
```

---

### Shop Deployment

**File:** `k8s/base/shop/deployment.yaml`

Runs 2 replicas of the `nelsonvillam/shop` image. Each build is tagged with the Jenkins build number (e.g. `nelsonvillam/shop:42`). The Jenkins pipeline injects the exact tag before applying — `:latest` never lands in the cluster.

#### Environment variables

Environment comes from two sources, both mounted via `envFrom`:

| Source | Kind | Contains |
|---|---|---|
| `shop-config` | ConfigMap | `SPRING_PROFILES_ACTIVE`, `REDIS_HOST`, `REDIS_PORT`, `ZIPKIN_URL` |
| `shop-secret` | Secret | `JWT_SECRET`, `ADMIN_PASSWORD`, `SPRING_DATA_MONGODB_URI` |

`SPRING_DATA_MONGODB_URI` as an environment variable overrides `spring.data.mongodb.uri` in `application-prod.properties` via Spring Boot's relaxed binding. This is how the multi-host replica set URI is injected without changing any code.

#### Health probes

Two probes are configured, hitting separate endpoints exposed by Spring Boot Actuator:

| Probe | Endpoint | What it checks | Effect if it fails |
|---|---|---|---|
| `readinessProbe` | `/actuator/health/readiness` | MongoDB connection, Redis connection, app readiness | Pod removed from Service — no traffic sent to it |
| `livenessProbe` | `/actuator/health/liveness` | JVM is alive and not deadlocked | Pod restarted by kubelet |

These endpoints are enabled by `management.health.probes.enabled=true` in `application.properties`. Before this property was added, only `/actuator/health` existed — it couldn't be used for both probes independently.

**Why separate probes matter:**

A pod can be alive (JVM running, responds to liveness) but not ready (MongoDB is temporarily unavailable). With separate probes:
- The pod is removed from the load balancer until MongoDB recovers
- The pod is NOT restarted, because the JVM itself is fine
- Once MongoDB recovers, readiness passes and traffic resumes automatically

---

### ConfigMap

**File:** `k8s/shop/configmap.yaml`

Holds non-sensitive environment variables:

```yaml
SPRING_PROFILES_ACTIVE: prod
REDIS_HOST: redis
REDIS_PORT: "6379"
ZIPKIN_URL: http://zipkin:9411
```

These use short service names (`redis`, `zipkin`) instead of fully-qualified domain names. Within the same Kubernetes namespace, DNS resolves short names automatically:

```
redis   → redis.shop.svc.cluster.local   → <ClusterIP>
zipkin  → zipkin.shop.svc.cluster.local  → <ClusterIP>
```

---

### Ingress

**File:** `k8s/shop/ingress.yaml`

An nginx `Ingress` that routes external HTTP traffic to the shop Service. The current configuration has no `host:` filter and accepts requests for **any hostname**, which allows direct access via the load balancer URL without needing a custom domain or DNS setup:

```
http://<load-balancer-hostname>/swagger-ui/index.html
http://<load-balancer-hostname>/actuator/health
```

When you have a real domain, add a `host:` field to the rule and update the DNS CNAME to point to the load balancer hostname:

```yaml
rules:
  - host: shop.yourdomain.com
    http:
      paths:
        - path: /
          pathType: Prefix
          backend:
            service:
              name: shop
              port:
                number: 80
```

For TLS, add a `tls` section and a `cert-manager` annotation:

```yaml
metadata:
  annotations:
    cert-manager.io/cluster-issuer: letsencrypt-prod
spec:
  tls:
    - hosts:
        - shop.yourdomain.com
      secretName: shop-tls
```

> **Host mismatch causes 404:** If a `host:` value is set in the Ingress but your browser or `curl` sends a different `Host` header (e.g., the raw load balancer hostname), nginx returns 404. Either remove the `host:` field for development, or add the LB hostname to `/etc/hosts` pointing to its IP.

---

## Service Discovery

Kubernetes DNS resolves service names within the `shop` namespace without any configuration:

| Component | Service name | Full DNS name | Port |
|---|---|---|---|
| MongoDB (per-pod) | `mongo-headless` | `mongo-{0,1,2}.mongo-headless.shop.svc.cluster.local` | 27017 |
| MongoDB (any pod) | `mongo` | `mongo.shop.svc.cluster.local` | 27017 |
| Redis | `redis` | `redis.shop.svc.cluster.local` | 6379 |
| Zipkin | `zipkin` | `zipkin.shop.svc.cluster.local` | 9411 |
| Shop API | `shop` | `shop.shop.svc.cluster.local` | 80 |

The shop app, Redis, and Zipkin use the **short names** (`redis`, `zipkin`) and K8s DNS resolves them. MongoDB uses the **fully-qualified per-pod names** in the replica set URI so the driver can independently address each member.

### How it works

Every pod runs a DNS resolver that queries `kube-dns` (or CoreDNS). When the shop app resolves `redis`:

```
redis
  → redis.shop                        (namespace appended)
  → redis.shop.svc                    (service domain appended)
  → redis.shop.svc.cluster.local      (full cluster domain)
  → 10.96.0.42                        (ClusterIP assigned to the redis Service)
```

The ClusterIP is a virtual IP managed by `kube-proxy`. Traffic to it is load-balanced across healthy pods via iptables or IPVS rules.

### Headless vs regular service — MongoDB

The two MongoDB services serve different purposes:

| Service | Type | When to use |
|---|---|---|
| `mongo-headless` | Headless (`clusterIP: None`) | Referenced in the MongoDB URI — returns individual pod IPs, required for RS member addressing |
| `mongo` | ClusterIP | Ad-hoc tooling: `kubectl exec`, monitoring agents, one-off `mongosh` sessions |

If the shop app used the regular `mongo` ClusterIP service, the driver would connect to a random pod each time and would not be able to track replica set topology (primary vs secondary) or route write vs read operations correctly.

---

## CI/CD Integration

The Jenkins `Deploy to Kubernetes` stage uses ESO for secret management. It only needs to create the `aws-credentials` Kubernetes Secret (from Jenkins' ambient AWS credentials) and then wait for ESO to confirm all secrets are synced before deploying the new image:

```groovy
stage('Deploy to Kubernetes') {
    steps {
        sh """
            # Switch to local cluster (replace with EKS context for cloud deploy)
            kubectl config use-context docker-desktop

            # Apply manifests — creates/updates ExternalSecret + SecretStore resources
            kubectl apply -k k8s/overlays/local/

            # Create aws-credentials secret for ESO (no xtrace — contains credentials)
            set +x
            kubectl create secret generic aws-credentials \
                --namespace shop \
                --from-literal=access-key-id="\${AWS_ACCESS_KEY_ID}" \
                --from-literal=secret-access-key="\${AWS_SECRET_ACCESS_KEY}" \
                --from-literal=session-token="\${AWS_SESSION_TOKEN:-}" \
                --save-config --dry-run=client -o yaml | kubectl apply -f -
            set -x

            # Wait for ESO to sync all secrets from AWS
            for es in mongodb-credentials mongodb-keyfile shop-secret; do
                kubectl wait externalsecret/\$es \
                    --namespace shop --for=condition=Ready --timeout=60s
            done

            # Deploy with pinned image tag
            sed 's|${IMAGE_NAME}:latest|${IMAGE_NAME}:${IMAGE_TAG}|g' \
                k8s/base/shop/deployment.yaml | kubectl apply -f -

            kubectl apply -f k8s/base/shop/configmap.yaml
            kubectl apply -f k8s/base/shop/service.yaml
            kubectl apply -f k8s/base/shop/ingress.yaml
        """
        sh "kubectl rollout status deployment/shop --namespace shop --timeout=5m"
    }
    post {
        failure {
            sh "kubectl rollout undo deployment/shop --namespace shop || true"
        }
    }
}
```

### What happens on each push

```
git push
  → Jenkins pipeline triggered
  → Tests, lint, SonarQube quality gate
  → Gradle bootJar (shop)
  → Docker Build & Push (parallel):
      shop:    nelsonvillam/shop:42      ← pinned build tag
               nelsonvillam/shop:latest  ← floating alias (not used in K8s)
      gateway: cd gateway && ./gradlew bootJar
               nelsonvillam/gateway:42
               nelsonvillam/gateway:latest
  → kubectl apply -k k8s/overlays/local/ (creates ExternalSecrets + SecretStore)
  → aws-credentials k8s Secret upserted from Jenkins AWS env vars
  → ESO syncs 3 secrets from AWS Secrets Manager → Kubernetes Secrets
  → sed rewrites shop deployment:    image: ...shop:42
  → sed rewrites gateway deployment: image: ...gateway:42
  → kubectl apply → K8s creates new ReplicaSets for both
  → Rolling updates: new pods start, pass readiness probes
  → Old pods terminated
  → kubectl rollout status (shop + gateway) blocks pipeline until complete
  → On failure: kubectl rollout undo for both shop and gateway
```

### Jenkins IAM requirements

The IAM user or role running Jenkins needs:

| Permission | Resource |
|---|---|
| `secretsmanager:GetSecretValue`, `secretsmanager:DescribeSecret` | `arn:aws:secretsmanager:<region>:<account>:secret:shop/*` |
| `kubectl` access | Kubeconfig pointed at the target cluster |

> For EKS: also add `eks:DescribeCluster` and an entry in the cluster `aws-auth` ConfigMap.

### Why `:latest` is replaced before apply

Docker Hub tags are mutable — `:latest` today and `:latest` tomorrow point to different image digests. If the Deployment said `image: ...shop:latest`:

- `kubectl apply` would see no change in the spec (same tag, just different digest)
- Kubernetes would not trigger a rollout
- `kubectl rollout undo` would roll back to the same tag, not the previous image

Pinning `image: ...shop:42` makes every build produce a spec change, which forces a rollout and makes every build individually reversible.

---

## Rolling Back

### Automatic rollback (CI-triggered)

If `kubectl rollout status` times out or the stage fails, the post-failure block runs:

```bash
kubectl rollout undo deployment/shop --namespace shop
```

This restores the previous `ReplicaSet` — Kubernetes keeps the last two `ReplicaSets` by default.

### Manual rollback

```bash
# See rollout history
kubectl rollout history deployment/shop --namespace shop

# Roll back to the previous revision
kubectl rollout undo deployment/shop --namespace shop

# Roll back to a specific revision
kubectl rollout undo deployment/shop --namespace shop --to-revision=3
```

---

## Scaling

### Horizontal scaling (more replicas)

```bash
# Scale shop to 4 replicas
kubectl scale deployment/shop --replicas=4 --namespace shop
```

Redis and Zipkin can be scaled the same way. MongoDB's replica set size requires updating the StatefulSet replicas and re-running the RS init Job to add new members.

### Resource limits

Each pod has requests and limits defined:

| Component | CPU request | CPU limit | Memory request | Memory limit |
|---|---|---|---|---|
| shop | 250m | 1 | 512Mi | 1Gi |
| mongo | 250m | 1 | 512Mi | 1Gi |
| redis | 100m | 500m | 128Mi | 256Mi |
| zipkin | 100m | 500m | 256Mi | 512Mi |

- **Requests**: guaranteed resources — used by the scheduler to decide which node a pod lands on
- **Limits**: hard caps — a pod that exceeds its memory limit is OOM-killed and restarted

---

## Useful Commands

```bash
# Watch pod status across the namespace
kubectl get pods --namespace shop --watch

# Tail shop app logs
kubectl logs --namespace shop -l app=shop -f

# Connect to mongo-0 with mongosh
kubectl exec --namespace shop -it mongo-0 -- mongosh \
  -u <user> -p <password> --authenticationDatabase admin

# Check replica set status (quick summary)
kubectl exec --namespace shop mongo-0 -- \
  mongosh --quiet --eval "rs.status().members.forEach(m => print(m.name, m.stateStr))"

# Describe a pod (shows events, probe failures, resource pressure)
kubectl describe pod mongo-0 --namespace shop

# Check RS init Job outcome
kubectl logs -l job-name=mongodb-rs-init --namespace shop

# Restart shop pods (forces pods to pick up updated secret values)
kubectl rollout restart deployment/shop --namespace shop

# Forward Zipkin UI locally
kubectl port-forward svc/zipkin 9411:9411 --namespace shop
# Then open: http://localhost:9411

# View cluster events sorted by time
kubectl get events --namespace shop --sort-by='.lastTimestamp'
```

---

## Common Problems

| Problem | Cause | Fix |
|---|---|---|
| `mongo-0` pod stuck in `Init:CrashLoopBackOff` | keyFile permissions not fixed | Check init container logs: `kubectl logs mongo-0 -c keyfile-init -n shop` |
| `mongo-1` / `mongo-2` not joining RS | keyFile mismatch between pods | All pods must use the same Secret; wait for ESO sync then restart pods |
| MongoDB crash: `invalid char in key file` | keyfile synced incorrectly from AWS | `kubectl describe externalsecret mongodb-keyfile -n shop` — check Events |
| Shop pods stuck in `0/1 Running` | Readiness probe failing | `kubectl exec -it <pod> -n shop -- curl localhost:8080/actuator/health` |
| `SPRING_DATA_MONGODB_URI` not set | `shop-secret` ExternalSecret not yet synced | `kubectl get externalsecrets -n shop` — wait for `SecretSynced` then restart pods |
| ExternalSecret READY = False | SecretStore not ready or AWS auth failed | `kubectl describe externalsecret <name> -n shop` — check Events for auth errors |
| SecretStore READY = False | `aws-credentials` Secret missing (local) or IRSA misconfigured (EKS) | Re-apply the aws-credentials Secret; for EKS verify the service account annotation |
| ESO sync fails: `AccessDenied` | IAM policy missing `secretsmanager:GetSecretValue` | Attach policy for `arn:aws:secretsmanager:<region>:<account>:secret:shop/*` |
| RS init Job keeps retrying | mongo-0 not ready, or ESO hadn't synced credentials yet when Job pod started | Delete Job and reapply after secrets are synced: `kubectl delete job mongodb-rs-init -n shop && kubectl apply -f k8s/base/mongodb/rs-init-job.yaml` |
| PVCs stuck in `Pending` | EBS CSI driver not installed, or StorageClass uses removed provisioner | Install `aws-ebs-csi-driver` addon; confirm StorageClass uses `ebs.csi.aws.com` |
| StatefulSet won't update `storageClassName` | `volumeClaimTemplates` is immutable | Delete StatefulSet with `--cascade=orphan`, then reapply |
| Rolling update stuck | New pods not passing readiness | Pipeline times out after 5m and `kubectl rollout undo` runs automatically |
| Ingress returns 404 | `host:` in Ingress doesn't match request's `Host` header | Remove `host:` for direct LB access; or add your domain to `/etc/hosts` |
| All requests return 401 unexpectedly | Gateway pod not ready or `JWT_SECRET` not synced | `kubectl get pods -n shop` — check gateway pod status; verify `shop-secret` ExternalSecret is `SecretSynced` |
| Gateway returns 401 but token is valid | Key derivation mismatch | Gateway and shop both use raw UTF-8 bytes (`Keys.hmacShaKeyFor(secret.getBytes(UTF_8))`). Confirm `JWT_SECRET` in `shop-secret` is a plain string, not Base64. |
| Swagger accessible but API calls blocked | Token not being sent | In Swagger UI click **Authorize** 🔒 → paste token → click **Authorize** before executing requests |
| `kubectl` can't connect to EKS | Wrong kubeconfig context | `aws eks update-kubeconfig --name shop-cluster --region sa-east-1` |
