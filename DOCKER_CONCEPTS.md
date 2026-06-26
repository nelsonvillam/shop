# Docker & Pipeline Concepts

---

## 1. Dockerfile vs docker-compose

**Dockerfile** answers *"how do I build the `shop` image?"*
It describes a recipe for a single image — compile the code, package the JAR, and define how to run it.

**docker-compose.yml** answers *"how do I run the whole application?"*
It doesn't build anything — it orchestrates multiple pre-built images together.

### Dockerfile (build time)

```dockerfile
FROM eclipse-temurin:21-jre-alpine     # minimal runtime base
WORKDIR /app
COPY build/libs/shop-0.0.1-SNAPSHOT.jar app.jar  # JAR built by Jenkins
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

The JAR is compiled by Jenkins (`./gradlew bootJar`) before `docker build` runs, so the Dockerfile is a single stage — it only packages and runs the artifact. See [DOCKERFILE_EXPLAINED.md](DOCKERFILE_EXPLAINED.md) for a line-by-line breakdown.

### docker-compose.yml (run time)

```
nelsonvillam/shop:latest  +  mongo:7  +  redis:7
       ↓                        ↓            ↓
   port 8081              mongo-data      redis-data
       └──────────── same Docker network ──────────┘
```

### How they relate in this project

```
Dockerfile  ──(docker build)──► nelsonvillam/shop:latest  (Docker Hub)
                                              │
docker-compose.yml  ──(docker compose up)────┘
                    also pulls mongo:7 and redis:7
```

The `Dockerfile` is used **once per build** to produce the image. The `docker-compose.yml` is used **at deploy time** to run that image alongside its dependencies. They never execute at the same time — one creates the artifact, the other consumes it.

---

## 2. docker-compose vs the Jenkins pipeline

The Jenkins pipeline is the **trigger and orchestrator**. docker-compose is the **deployment executor**, and only appears in the last stage.

### Flow

```
Jenkinsfile                          docker-compose.yml
──────────────────────────────────────────────────────────────
stage('Build')
  ./gradlew bootJar              ──► build/libs/shop-0.0.1-SNAPSHOT.jar

stage('Docker Build')
  docker build -t nelsonvillam/shop:BUILD_NUMBER .
  docker tag ... nelsonvillam/shop:latest        ──► image: nelsonvillam/shop:latest

stage('Docker Push')
  docker push nelsonvillam/shop:latest           ──► pushed to Docker Hub

stage('Deploy')
  withCredentials([mongo-user,                   ──► injects MONGO_USER
                   mongo-password])                          MONGO_PASSWORD
                                                                 │
  docker compose up -d  ──────────────────────────► mongo  (uses MONGO_USER/PASSWORD)
                                                    redis
                                                    shop   (pulls nelsonvillam/shop:latest)
```

### Key points

**1. The pipeline builds the image, docker-compose consumes it.**
The `docker-compose.yml` never builds — it always pulls `nelsonvillam/shop:latest`, which the pipeline just pushed to Docker Hub.

**2. Credentials flow from Jenkins into docker-compose.**
`MONGO_USER` and `MONGO_PASSWORD` are stored as Jenkins secrets. The Deploy stage injects them as environment variables, and docker-compose picks them up automatically to configure both MongoDB and the connection URI:
```
mongodb://user:pass@mongo:27017/shop?authSource=admin
```

**3. docker-compose is only involved in the last stage.**
The earlier stages — checkout, unit test, integration test, SonarQube analysis, quality gate, build, docker build, docker push — have nothing to do with docker-compose. It only appears at deploy time.

**4. Every push to `main` triggers a full redeploy.**
The pipeline ends by running `docker compose down` followed by `docker compose up -d`, bringing up a fresh stack with the newly built image while preserving data in the `mongo-data` and `redis-data` named volumes.

---

## Summary

| | Dockerfile | docker-compose.yml | Jenkinsfile |
|---|---|---|---|
| **Role** | Build recipe | Run orchestrator | Pipeline controller |
| **Scope** | Single image | Multiple services | All stages end-to-end |
| **When** | `docker build` (CI) | `docker compose up` (deploy) | On every push to `main` |
| **Knows about** | Source code | Images + networks + volumes | Everything |
