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
                         ┌──────────────────────────────────────────┐
                         │               shop namespace             │
                         │                                          │
  internet ─────────────▶│  Ingress (nginx)                        │
                         │     shop.example.com → shop:80           │
                         │         │                                │
                         │  ┌──────▼──────────────┐                │
                         │  │   shop Deployment    │                │
                         │  │   (2 replicas)       │                │
                         │  │                      │                │
                         │  │  /health/liveness  ◀─┼── kubelet     │
                         │  │  /health/readiness ◀─┼── kubelet     │
                         │  └──┬──────────┬──────┘                │
                         │     │          │          │             │
                         │  ┌──▼───┐  ┌───▼──┐  ┌──▼──────┐     │
                         │  │mongo │  │redis │  │ zipkin  │     │
                         │  │  SS  │  │ Dep  │  │  Dep    │     │
                         │  │ 3pods│  │1 pod │  │ 1 pod   │     │
                         │  └──────┘  └──────┘  └─────────┘     │
                         └──────────────────────────────────────────┘

SS = StatefulSet
```

---

## Directory Structure

```
k8s/
├── namespace.yaml                # "shop" namespace — all resources live here
├── kustomization.yaml            # apply everything with: kubectl apply -k k8s/
│
├── mongodb/
│   ├── credentials-secret.yaml  # MONGO_USER + MONGO_PASSWORD placeholders
│   ├── keyfile-secret.yaml      # shared RS auth keyfile placeholder
│   ├── statefulset.yaml         # 3-node replica set with keyFile auth
│   ├── headless-service.yaml    # stable DNS names for each pod
│   ├── service.yaml             # ClusterIP for tooling / ad-hoc access
│   └── rs-init-job.yaml         # Job that calls rs.initiate() once
│
├── redis/
│   ├── deployment.yaml
│   └── service.yaml
│
├── zipkin/
│   ├── deployment.yaml
│   └── service.yaml
│
└── shop/
    ├── configmap.yaml            # non-sensitive env vars (REDIS_HOST, ZIPKIN_URL)
    ├── secret.yaml               # JWT_SECRET, ADMIN_PASSWORD, MongoDB URI placeholders
    ├── deployment.yaml           # 2 replicas, liveness + readiness probes
    ├── service.yaml              # ClusterIP port 80 → 8080
    └── ingress.yaml              # nginx Ingress with host-based routing
```

---

## Prerequisites

| Requirement | Notes |
|---|---|
| Kubernetes cluster | EKS, GKE, AKS, or local via `minikube` / `kind` |
| `kubectl` ≥ 1.14 | Built-in Kustomize support included |
| nginx Ingress Controller | `kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/controller-v1.10.0/deploy/static/provider/cloud/deploy.yaml` |
| Docker Hub image pushed | Jenkins pipeline must have run at least once |
| `kubectl` configured | `~/.kube/config` pointed at the target cluster |

---

## One-Time Cluster Setup

Secrets are provisioned once by the cluster admin and are **never touched by the CI pipeline**. Run these commands before the first deploy.

### Step 1 — Create the namespace

```bash
kubectl apply -f k8s/namespace.yaml
```

### Step 2 — MongoDB credentials

```bash
kubectl create secret generic mongodb-credentials \
  --namespace shop \
  --from-literal=username=shopuser \
  --from-literal=password=$(openssl rand -base64 32)
```

### Step 3 — MongoDB replica set keyfile

All 3 replica set members must share the exact same keyfile. MongoDB uses it to authenticate internal RS traffic — without it, secondary nodes cannot join the primary.

```bash
kubectl create secret generic mongodb-keyfile \
  --namespace shop \
  --from-literal=keyfile="$(openssl rand -base64 756)"
```

> **Why 756 bytes?** MongoDB's minimum keyfile size is 6 bytes and maximum is 1024 bytes. 756 bytes base64-encoded is a common standard that produces a strong shared secret.

### Step 4 — Shop app secrets

Replace `MONGO_USER` and `MONGO_PASSWORD` in the URI with the values from Step 2.

```bash
kubectl create secret generic shop-secret \
  --namespace shop \
  --from-literal=JWT_SECRET=$(openssl rand -base64 64) \
  --from-literal=ADMIN_PASSWORD=changeme \
  --from-literal=SPRING_DATA_MONGODB_URI="mongodb://shopuser:PASS@mongo-0.mongo-headless:27017,mongo-1.mongo-headless:27017,mongo-2.mongo-headless:27017/shop?authSource=admin&replicaSet=rs0"
```

> The URI lists all 3 replica set members. This lets the MongoDB driver survive a primary re-election without dropping connections — the driver queries each member to find out who is currently primary.

---

## Deploy

### First deploy

```bash
# Apply all non-secret resources (namespace, StatefulSet, Deployments, Services, Ingress)
kubectl apply -k k8s/

# Wait for mongo-0 to be ready before initiating the replica set
kubectl wait --for=condition=ready pod/mongo-0 --namespace shop --timeout=120s

# Run the replica set init Job (idempotent — safe to run again later)
kubectl apply -f k8s/mongodb/rs-init-job.yaml
```

### Subsequent deploys

The Jenkins pipeline handles this automatically. To deploy manually:

```bash
kubectl apply -k k8s/
kubectl rollout status deployment/shop --namespace shop --timeout=5m
```

---

## Components

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

This mirrors exactly what `scripts/mongo-init.sh` does in Docker Compose via `exec docker-entrypoint.sh mongod ...`.

#### Persistent storage

Each pod gets a 10 Gi `PersistentVolumeClaim` via `volumeClaimTemplates`. These are provisioned automatically by the cluster's default `StorageClass` (e.g. `gp2` on EKS, `standard` on GKE). Data survives pod restarts and rescheduling.

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

### Shop Deployment

**File:** `k8s/shop/deployment.yaml`

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

With a single shared probe, the pod would be restarted unnecessarily.

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

### Secret

**File:** `k8s/shop/secret.yaml`

Contains placeholder values that must be replaced before the first apply. In practice, the cluster admin creates these secrets with `kubectl create secret` (see [One-Time Cluster Setup](#one-time-cluster-setup)) so the YAML file in the repo only documents what keys are expected.

> **Never commit real secrets to Git.** The placeholder values in `shop/secret.yaml` and `mongodb/credentials-secret.yaml` are documentation only.

---

### Service

**File:** `k8s/shop/service.yaml`

A `ClusterIP` Service that routes traffic to any shop pod on port `8080`, exposed internally as port `80`. The Ingress sends traffic here.

---

### Ingress

**File:** `k8s/shop/ingress.yaml`

An nginx `Ingress` that routes external HTTP traffic to the shop Service:

```
shop.example.com  →  shop Service :80  →  shop pods :8080
```

Change the `host` field to your actual domain before applying. For TLS, add a `tls` section and a `cert-manager` annotation:

```yaml
metadata:
  annotations:
    cert-manager.io/cluster-issuer: letsencrypt-prod
spec:
  tls:
    - hosts:
        - shop.yourdomain.com
      secretName: shop-tls
  rules:
    - host: shop.yourdomain.com
      ...
```

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

The Jenkins `Deploy to Kubernetes` stage:

```groovy
withCredentials([file(credentialsId: 'k8s-kubeconfig', variable: 'KUBECONFIG')]) {
    sh """
        sed 's|${IMAGE_NAME}:latest|${IMAGE_NAME}:${IMAGE_TAG}|g' \
            k8s/shop/deployment.yaml | kubectl apply -f -

        kubectl apply -f k8s/shop/configmap.yaml
        kubectl apply -f k8s/shop/service.yaml
        kubectl apply -f k8s/shop/ingress.yaml
    """
    sh "kubectl rollout status deployment/shop -n shop --timeout=5m"
}
```

### What happens on each push

```
git push
  → Jenkins pipeline triggered
  → Tests, lint, SonarQube quality gate
  → Gradle bootJar
  → Docker image built and pushed:
      nelsonvillam/shop:42      ← pinned build tag
      nelsonvillam/shop:latest  ← floating alias (not used in K8s)
  → sed rewrites deployment.yaml: image: ...shop:42
  → kubectl apply → K8s creates new ReplicaSet
  → Rolling update: new pods start, pass readiness probes
  → Old pods terminated
  → kubectl rollout status blocks pipeline until complete
  → On failure: kubectl rollout undo restores previous ReplicaSet
```

### Credential required in Jenkins

| Credential ID | Type | Purpose |
|---|---|---|
| `k8s-kubeconfig` | Secret file | Authenticates `kubectl` with the cluster |

Add it under **Manage Jenkins → Credentials → Global → Add Credentials → Secret file**.

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
kubectl exec --namespace shop -it mongo-0 -- \
  mongosh -u <user> -p <password> --authenticationDatabase admin

# Check replica set status
kubectl exec --namespace shop -it mongo-0 -- \
  mongosh -u <user> -p <password> --authenticationDatabase admin \
  --eval "rs.status()"

# Describe a pod (shows events, probe failures, resource pressure)
kubectl describe pod mongo-0 --namespace shop

# Check RS init Job outcome
kubectl logs -l job-name=mongodb-rs-init --namespace shop
```

---

## Common Problems

| Problem | Cause | Fix |
|---|---|---|
| `mongo-0` pod stuck in `Init:CrashLoopBackOff` | keyFile permissions not fixed | Check init container logs: `kubectl logs mongo-0 -c keyfile-init -n shop` |
| `mongo-1` / `mongo-2` not joining RS | keyFile mismatch between pods | All pods must use the same Secret; recreate `mongodb-keyfile` secret and restart pods |
| Shop pods stuck in `0/1 Running` | Readiness probe failing | Check actuator health: `kubectl exec -it <pod> -n shop -- curl localhost:8080/actuator/health` |
| `SPRING_DATA_MONGODB_URI` not set | Secret not created before deploy | Run the one-time setup commands in [One-Time Cluster Setup](#one-time-cluster-setup) |
| RS init Job keeps retrying | mongo-0 not ready yet | Wait for `kubectl wait --for=condition=ready pod/mongo-0 -n shop --timeout=120s` before applying the Job |
| Rolling update stuck | New pods not passing readiness | The pipeline times out after 5m and `kubectl rollout undo` runs automatically |
| Ingress returns 404 | Host header mismatch | Ensure the `host` in `ingress.yaml` matches your request's `Host` header or domain |
| `kubectl` can't connect | Wrong kubeconfig context | Run `kubectl config get-contexts` and switch with `kubectl config use-context <name>` |
