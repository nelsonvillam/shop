# CI/CD Pipeline

This project uses a Jenkins declarative pipeline to build, test, publish, and deploy the application automatically on every push to `main`.

---

## Infrastructure

| Component | Technology | Purpose |
|---|---|---|
| CI/CD server | Jenkins (Docker container) | Runs the pipeline |
| Docker daemon | Docker-in-Docker (DinD) | Builds and runs containers inside Jenkins |
| Image registry | Docker Hub (`nelsonvillam/shop`) | Stores built images |
| Database | MongoDB 7 | Persistent data store |
| Cache | Redis 7 | Application-level caching |

Jenkins and DinD run as Docker containers on the same host, connected via a shared Docker network. Jenkins communicates with the DinD daemon over TLS on port 2376.

---

## Pipeline stages

```
Checkout → Unit Test → Integration Test → Docker Build → Docker Push → Deploy
```

### 1. Checkout
Pulls the latest code from the GitHub repository.

### 2. Unit Test
Runs inside an `eclipse-temurin:21-jdk` container.

```bash
./gradlew test --no-daemon
```

Test results are published to Jenkins via JUnit reporter (`build/test-results/test/**/*.xml`).

### 3. Integration Test
Runs inside an `eclipse-temurin:21-jdk` container with the Docker socket mounted so Testcontainers can spin up a real MongoDB instance.

```bash
./gradlew integrationTest --no-daemon
```

Test results are published to Jenkins via JUnit reporter (`build/test-results/integrationTest/**/*.xml`).

### 4. Docker Build
Builds the application image and tags it with the Jenkins build number and `latest`.

```bash
docker build -t nelsonvillam/shop:<BUILD_NUMBER> .
docker tag nelsonvillam/shop:<BUILD_NUMBER> nelsonvillam/shop:latest
```

### 5. Docker Push
Logs in to Docker Hub using the `dockerhub-creds` Jenkins credential and pushes both tags.

```bash
docker push nelsonvillam/shop:<BUILD_NUMBER>
docker push nelsonvillam/shop:latest
```

### 6. Deploy
Tears down any previous stack and brings up the full application using `docker compose`. MongoDB credentials are injected from Jenkins secrets (`mongo-user`, `mongo-password`).

```bash
docker stop shop || true
docker rm shop || true
docker compose down --remove-orphans || true
docker compose up -d
```

---

## Deployment stack (`docker-compose.yml`)

Three services are started together on the same Docker network:

| Service | Image | Port |
|---|---|---|
| `mongo` | `mongo:7` | 27017 (internal) |
| `redis` | `redis:7-alpine` | 6379 (internal) |
| `shop` | `nelsonvillam/shop:latest` | 8081 → 8080 |

The shop service connects to MongoDB using:
```
mongodb://<user>:<password>@mongo:27017/shop?authSource=admin
```

`authSource=admin` is required because the root user created by `MONGO_INITDB_ROOT_USERNAME` is stored in the `admin` database.

Data is persisted across deployments via named Docker volumes (`mongo-data`, `redis-data`).

---

## Jenkins credentials

| Credential ID | Type | Used in stage |
|---|---|---|
| `dockerhub-creds` | Username/Password | Docker Push |
| `mongo-user` | Secret text | Deploy |
| `mongo-password` | Secret text | Deploy |

---

## Accessing the app

After a successful pipeline run the app is available at:

| URL | Description |
|---|---|
| `http://localhost:8081/swagger-ui/index.html` | Swagger UI |
| `http://localhost:8081/v3/api-docs` | Raw OpenAPI spec |
| `http://localhost:8081/api/products` | Products endpoint |
