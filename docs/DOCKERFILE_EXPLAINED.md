# Dockerfile Explained

This Dockerfile uses a **single-stage build**. The JAR is compiled by Jenkins before `docker build` runs, so the Dockerfile is only responsible for packaging and running the application.

---

## Why single-stage?

The previous approach compiled the source code inside Docker (multi-stage build). While self-contained, it made Docker responsible for both building and packaging. Since Jenkins already has a dedicated `Build` stage that runs `./gradlew bootJar`, there is no need to repeat that work inside the Dockerfile.

| | Multi-stage (before) | Single-stage (now) |
|---|---|---|
| **Who compiles** | Docker (inside container) | Jenkins (on the agent) |
| **Dockerfile complexity** | High (2 stages, 14 lines) | Low (1 stage, 5 lines) |
| **Build speed** | Slower (Gradle runs inside Docker) | Faster (Gradle cache on Jenkins agent) |
| **Image size** | Same final result | Same final result |
| **Separation of concerns** | Docker does too much | Jenkins builds, Docker packages |

---

## Line by line

```dockerfile
FROM eclipse-temurin:21-jre-alpine
```
Starts from an official Java 21 JRE on Alpine Linux. Key choices:
- **JRE instead of JDK** — only the runtime is needed to run a JAR, not the compiler. The compilation already happened in Jenkins.
- **Alpine** — a minimal Linux distribution (~5 MB) that keeps the final image small (~90 MB total).

---

```dockerfile
WORKDIR /app
```
Sets `/app` as the working directory inside the container. All subsequent instructions run from this path.

---

```dockerfile
COPY build/libs/shop-0.0.1-SNAPSHOT.jar app.jar
```
Copies the JAR produced by `./gradlew bootJar` (run by Jenkins in the `Build` stage) into the image. This is the only artifact the image needs — the JAR already contains the compiled code and all dependencies bundled inside.

---

```dockerfile
EXPOSE 8080
```
Documents that the container listens on port 8080. This is informational only — it does not publish the port to the host. The actual port binding happens in `docker-compose.yml` (`8081:8080`).

---

```dockerfile
ENTRYPOINT ["java", "-jar", "app.jar"]
```
Defines the command that runs when the container starts. Launches the Spring Boot application from the JAR. The JSON array form ensures the process runs directly without a shell wrapper, so signals like `SIGTERM` are handled correctly by the JVM.

---

## What the final image contains

| What | Why |
|---|---|
| Alpine Linux | Minimal base OS |
| Java 21 JRE | Runtime to execute the JAR |
| `app.jar` | The compiled Spring Boot app with all dependencies inside |

No source code, no Gradle, no JDK — just enough to run the application.

---

## How it fits in the pipeline

```
Jenkins: Build stage
  └── ./gradlew bootJar  →  build/libs/shop-0.0.1-SNAPSHOT.jar

Jenkins: Docker Build stage
  └── docker build       →  COPY build/libs/...jar app.jar  →  final image
```
