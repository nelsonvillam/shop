# Production Deployment Guide

This app is a Spring Boot 3 + Java 21 REST API backed by MongoDB and Redis, built with Gradle and deployed via Docker and Jenkins.

---

## Infrastructure Overview

```
[Developer laptop]
        ↓  git push
[GitHub repo]
        ↓  webhook (HTTP POST)
[Jenkins Server]  →  builds, tests, pushes Docker image
        ↓  SSH
[Production Server]  →  runs the app container
        │
        ├── MongoDB container
        └── Redis container
```

You need three separate machines:
| Machine | Purpose |
|---------|---------|
| Jenkins Server | Runs CI/CD pipelines |
| Production Server | Runs the app, MongoDB, Redis |
| Docker Registry | Stores built images (Docker Hub or private) |

---

## Prerequisites

### Jenkins Server
- Ubuntu 22.04 LTS (recommended)
- Java 21 installed
- Docker installed and Jenkins user added to the `docker` group
- Jenkins installed and running on port 8080
- Plugins: **GitHub**, **Docker Pipeline**, **SSH Agent**, **JUnit**
- Publicly accessible (so GitHub webhooks can reach it)

### Production Server
- Ubuntu 22.04 LTS
- Docker and Docker Compose installed
- Firewall open on port 8080 (app), 27017 (MongoDB, internal only), 6379 (Redis, internal only)
- SSH key from Jenkins server authorized in `~/.ssh/authorized_keys`

---

## Step 1 — Set Up the Production Server

### 1.1 Install Docker
```bash
sudo apt update && sudo apt install -y docker.io docker-compose
sudo systemctl enable docker && sudo systemctl start docker
```

### 1.2 Create a Docker network
```bash
docker network create shop-network
```

### 1.3 Run MongoDB
```bash
docker run -d \
  --name mongo \
  --network shop-network \
  --restart unless-stopped \
  -v mongo-data:/data/db \
  -e MONGO_INITDB_DATABASE=shop \
  mongo:7
```

### 1.4 Run Redis
```bash
docker run -d \
  --name redis \
  --network shop-network \
  --restart unless-stopped \
  redis:7-alpine
```

---

## Step 2 — Configure Jenkins

### 2.1 Add credentials
Go to **Manage Jenkins → Credentials → Global → Add Credentials**:

| ID | Type | Purpose |
|----|------|---------|
| `dockerhub-creds` | Username/Password | Docker Hub login |
| `server-ssh-key` | SSH Username with private key | SSH into production server |

### 2.2 Create the pipeline job
1. New Item → **Multibranch Pipeline**
2. Branch Sources → GitHub → enter your repo URL
3. Credentials: add a GitHub token if the repo is private
4. Build Configuration → by Jenkinsfile (default)
5. Save — Jenkins will scan all branches immediately

### 2.3 Update the Jenkinsfile
Edit the two placeholders in `Jenkinsfile`:
```groovy
IMAGE_NAME = 'your-dockerhub-user/shop'   // ← your Docker Hub username
```
```groovy
ssh -o StrictHostKeyChecking=no user@your-server   // ← your server IP and SSH user
```

---

## Step 3 — Set Up the GitHub Webhook

1. Go to your GitHub repo → **Settings → Webhooks → Add webhook**
2. Payload URL: `http://<jenkins-server-ip>:8080/github-webhook/`
3. Content type: `application/json`
4. Event: **Just the push event**
5. Save

Every push to any branch will now trigger Jenkins automatically.

---

## Step 4 — First Deployment

1. Push the `Jenkinsfile` and `Dockerfile` to your `main` branch
2. Jenkins picks up the push via webhook and runs the pipeline:
   - Compiles the code and runs unit tests
   - Runs integration tests (requires Docker on the Jenkins server)
   - Builds a Docker image tagged with the Jenkins build number
   - Pushes the image to Docker Hub
   - SSHs into the production server and runs the new container

### What the deploy stage does on the server
```bash
docker pull your-dockerhub-user/shop:<build-number>
docker stop shop && docker rm shop
docker run -d \
  --name shop \
  --network shop-network \
  --restart unless-stopped \
  -p 8080:8080 \
  -e SPRING_DATA_MONGODB_URI=mongodb://mongo:27017/shop \
  -e SPRING_REDIS_HOST=redis \
  -e SPRING_REDIS_PORT=6379 \
  your-dockerhub-user/shop:<build-number>
```

---

## Environment Variables

Pass these to the container at runtime. Never hardcode them in source code.

| Variable | Example value | Description |
|----------|--------------|-------------|
| `SPRING_DATA_MONGODB_URI` | `mongodb://mongo:27017/shop` | MongoDB connection |
| `SPRING_REDIS_HOST` | `redis` | Redis hostname |
| `SPRING_REDIS_PORT` | `6379` | Redis port |
| `SERVER_PORT` | `8080` | App HTTP port |
| `SPRING_PROFILES_ACTIVE` | `prod` | Active Spring profile |

Store secrets (passwords, API keys) as Jenkins credentials and inject them as environment variables in the `Jenkinsfile` — never commit them to Git.

---

## Branch Strategy

Using a Multibranch Pipeline, each branch triggers a different behavior:

| Branch | Build | Test | Deploy |
|--------|-------|------|--------|
| `main` | yes | yes | Production |
| `develop` | yes | yes | Staging (if configured) |
| `feature/*` | yes | yes | No deploy |

Enforce this in the `Jenkinsfile` with `when { branch 'main' }` on the Deploy stage.

---

## Rolling Back

Each build produces a uniquely tagged image (`shop:<build-number>`). To roll back:

```bash
# On the production server
docker stop shop && docker rm shop
docker run -d \
  --name shop \
  --network shop-network \
  --restart unless-stopped \
  -p 8080:8080 \
  -e SPRING_DATA_MONGODB_URI=mongodb://mongo:27017/shop \
  -e SPRING_REDIS_HOST=redis \
  your-dockerhub-user/shop:<previous-build-number>
```

Old images remain in Docker Hub until you manually delete them, so any prior build can be restored instantly.

---

## Health Check

Verify the app is running after deployment:
```bash
curl http://your-server:8080/actuator/health
```

> Add `spring-boot-starter-actuator` to `build.gradle` if this endpoint is not available.

---

## Common Problems

| Problem | Cause | Fix |
|---------|-------|-----|
| Pipeline not triggered | Webhook not reaching Jenkins | Check Jenkins is publicly accessible; verify webhook delivery in GitHub |
| Docker build fails | Jar not found | Run `./gradlew build -x integrationTest` before `docker build` |
| Integration tests fail on Jenkins | Docker not available | Add Jenkins user to the `docker` group and restart Jenkins |
| App can't connect to MongoDB | Wrong URI or network | Ensure all containers are on the same Docker network (`shop-network`) |
| Deploy stage skipped | Wrong branch condition | Check `when { branch 'main' }` matches your actual branch name |
