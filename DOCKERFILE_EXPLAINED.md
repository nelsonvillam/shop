# Dockerfile Explained

This Dockerfile uses a **multi-stage build** — two `FROM` instructions, two separate environments. The first stage compiles the code. The second stage produces a lean image with only what is needed to run the app.

---

## Stage 1 — Build

```dockerfile
FROM eclipse-temurin:21-jdk AS build
```
Starts from an official Java 21 JDK image. The `AS build` label names this stage so the second stage can reference it later. A JDK is required here because we need to compile source code.

---

```dockerfile
WORKDIR /app
```
Sets `/app` as the working directory inside the container. All subsequent commands run from this path. Creates the directory if it does not exist.

---

```dockerfile
COPY gradlew .
COPY gradle gradle
```
Copies the Gradle wrapper script (`gradlew`) and the `gradle/wrapper/` directory into the container. These two lines are copied first — before the source code — so Docker can cache this layer. As long as the wrapper does not change, Docker skips re-downloading Gradle on every build.

---

```dockerfile
COPY build.gradle .
COPY settings.gradle .
```
Copies the build configuration files. Also copied before the source code for the same caching reason — if only `src/` changes, Docker reuses the layer where dependencies were already downloaded.

---

```dockerfile
RUN ./gradlew dependencies --no-daemon
```
Downloads all project dependencies declared in `build.gradle` and caches them inside this layer. `--no-daemon` tells Gradle not to start a background daemon process (daemons are useful locally but wasteful inside containers). This is the most expensive layer — it hits the network — but Docker caches it as long as `build.gradle` does not change.

---

```dockerfile
COPY src ./src
```
Copies the actual source code last. Placing this after the dependency download means Docker only re-runs the compilation steps below when the source code changes, not every time.

---

```dockerfile
RUN ./gradlew bootJar --no-daemon
```
Compiles the source code and packages the application into a single executable JAR file (`build/libs/shop-0.0.1-SNAPSHOT.jar`). `bootJar` is a Spring Boot Gradle task that bundles the app and all its dependencies into one self-contained JAR.

---

## Stage 2 — Runtime

```dockerfile
FROM eclipse-temurin:21-jre-alpine
```
Starts a brand-new image from scratch using a Java 21 JRE on Alpine Linux. Key differences from stage 1:
- **JRE instead of JDK** — only the runtime is needed to run a JAR, not the compiler.
- **Alpine** — a minimal Linux distribution (~5 MB). The final image is significantly smaller than if we had kept the JDK.

Everything from stage 1 (source code, Gradle, build tools) is discarded here.

---

```dockerfile
WORKDIR /app
```
Sets `/app` as the working directory in this runtime image.

---

```dockerfile
COPY --from=build /app/build/libs/shop-0.0.1-SNAPSHOT.jar app.jar
```
Copies only the compiled JAR from stage 1 (`build`) into this stage. Nothing else from the build environment comes across — no source code, no Gradle, no JDK. This is the core benefit of multi-stage builds.

---

```dockerfile
EXPOSE 8080
```
Documents that the container listens on port 8080. This is informational only — it does not actually publish the port to the host. The actual port binding happens in `docker-compose.yml` (`8081:8080`).

---

```dockerfile
ENTRYPOINT ["java", "-jar", "app.jar"]
```
Defines the command that runs when the container starts. Launches the Spring Boot application from the JAR. Using the JSON array form (`["java", "-jar", "app.jar"]`) instead of a plain string ensures the process runs directly without a shell wrapper, so signals like `SIGTERM` are handled correctly by the JVM.

---

## Why two stages?

| | Stage 1 (build) | Stage 2 (runtime) |
|---|---|---|
| **Base image** | `eclipse-temurin:21-jdk` | `eclipse-temurin:21-jre-alpine` |
| **Contains** | JDK, Gradle, source code, JAR | JAR only |
| **Image size** | ~600 MB | ~90 MB |
| **Purpose** | Compile and package | Run the app |

Only stage 2 becomes the final image that is pushed to Docker Hub and deployed.

---

## Layer caching strategy

The order of instructions is intentional to maximise Docker's layer cache:

```
COPY gradlew + gradle/     ← rarely changes → cached
COPY build.gradle          ← changes when deps change → cached until then
RUN ./gradlew dependencies ← expensive network step → cached
COPY src/                  ← changes often → always re-runs from here
RUN ./gradlew bootJar      ← re-runs only when src changes
```

This means a typical code change only re-runs the last two steps instead of downloading all dependencies from scratch every time.
