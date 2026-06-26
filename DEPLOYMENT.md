# Deployment Guide

This app is a Spring Boot 3 + Java 21 REST API backed by MongoDB and Redis, built with Gradle and deployed via Docker Compose on the same machine that runs Jenkins.

---

## Infrastructure Overview

```
[Developer laptop]
        ↓  git push
[GitHub repo]
        ↓  webhook (HTTP POST)
[Jenkins :8080]  →  builds, tests, pushes Docker image to Docker Hub
        ↓  docker compose up -d (same machine)
[shop :8081]  +  [MongoDB :27017]  +  [Redis :6379]
```

Everything runs on a single machine:

| Service | Port | Notes |
|---------|------|-------|
| Jenkins | 8080 | CI/CD server |
| shop | 8081 (host) → 8080 (container) | Spring Boot app |
| MongoDB | 27017 (internal only) | Not exposed to host |
| Redis | 6379 (internal only) | Not exposed to host |

---

## Prerequisites

- Docker and Docker Compose installed
- Jenkins running on port 8080
- Jenkins user has access to the Docker socket
- The following credentials configured in Jenkins (**Manage Jenkins → Credentials → Global**):

| Credential ID | Type | Purpose |
|---|---|---|
| `dockerhub-creds` | Username/Password | Push image to Docker Hub |
| `mongo-user` | Secret text | MongoDB username injected at deploy time |
| `mongo-password` | Secret text | MongoDB password injected at deploy time |

---

## How Deployment Works

The `Deploy` stage in the Jenkinsfile runs on the same host as Jenkins:

```bash
docker stop shop || true
docker rm shop || true
docker compose down --remove-orphans || true
docker compose up -d
```

`docker compose up -d` reads `docker-compose.yml` from the workspace and starts three services:

- **mongo** — MongoDB 7 with credentials from Jenkins secrets
- **redis** — Redis 7 (no auth)
- **shop** — the freshly built image `nelsonvillam/shop:latest`, exposed on port 8081

Named volumes (`mongo-data`, `redis-data`) persist data across deployments.

---

## Environment Variables

These are set by `docker-compose.yml` at runtime. Secrets come from Jenkins credentials and are never committed to Git.

| Variable | Value | Description |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `prod` | Activates `application-prod.properties` |
| `MONGO_USER` | from Jenkins secret | MongoDB username |
| `MONGO_PASSWORD` | from Jenkins secret | MongoDB password |
| `REDIS_HOST` | `redis` | Redis service name (Docker internal DNS) |
| `REDIS_PORT` | `6379` | Redis port |
| `SPRING_DATA_MONGODB_URI` | `mongodb://<user>:<pass>@mongo:27017/shop?authSource=admin` | Full MongoDB connection string |

`authSource=admin` is required because the root user is stored in the `admin` database.

---

## Testing the Deployed App

After a successful pipeline run:

```bash
# Swagger UI
open http://localhost:8081/swagger-ui/index.html

# Quick smoke tests
curl http://localhost:8081/api/products
curl http://localhost:8081/api/customers
curl http://localhost:8081/api/orders
```

---

## Rolling Back

Each build pushes two tags to Docker Hub: `nelsonvillam/shop:<BUILD_NUMBER>` and `nelsonvillam/shop:latest`. To roll back to a previous build:

1. Update `docker-compose.yml` to pin the image to the previous build number:
   ```yaml
   image: nelsonvillam/shop:<previous-build-number>
   ```
2. Run:
   ```bash
   docker compose up -d
   ```
3. Revert `docker-compose.yml` once the rollback is confirmed.

---

## Common Problems

| Problem | Cause | Fix |
|---------|-------|-----|
| Pipeline not triggered | Webhook not reaching Jenkins | Verify webhook delivery in GitHub → Settings → Webhooks |
| Integration tests fail on Jenkins | Docker socket not accessible | Add Jenkins user to the `docker` group and restart Jenkins |
| App can't connect to MongoDB | Wrong URI or auth | Check `SPRING_DATA_MONGODB_URI` and that `authSource=admin` is set |
| Port 8081 already in use | Previous container still running | Run `docker compose down` then `docker compose up -d` |
| SonarCloud analysis blocked | Automatic Analysis enabled | Disable it in SonarCloud → Administration → Analysis Method |
