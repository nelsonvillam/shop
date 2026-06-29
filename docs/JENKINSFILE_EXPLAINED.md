# Jenkinsfile Explained

Each of the three service repositories has its own `Jenkinsfile`. The shop pipeline is the most complete — it includes lint, tests, SonarQube, and quality gate. The gateway and ping-service pipelines are simpler since they have no automated tests.

---

## Pipeline Comparison

| Stage | shop | gateway | ping-service |
|---|---|---|---|
| Checkout | ✓ | ✓ | ✓ |
| Lint | ✓ | — | — |
| Compile Tests | ✓ | — | — |
| Unit Test | ✓ | — | — |
| Integration Test | ✓ | — | — |
| SonarQube Analysis | ✓ | — | — |
| Quality Gate | ✓ | — | — |
| Build (bootJar) | ✓ | ✓ | ✓ |
| Docker Build & Push | ✓ | ✓ | ✓ |
| Deploy to Kubernetes | ✓ | ✓ | ✓ |

---

## shop Jenkinsfile

### Structure at a glance

```
pipeline
├── agent
├── triggers
├── environment
├── stages
│   ├── Checkout
│   ├── Lint
│   ├── Compile Tests
│   ├── Tests (parallel, failFast)
│   │   ├── Unit Test
│   │   └── Integration Test
│   ├── SonarQube Analysis
│   ├── Quality Gate
│   ├── Build
│   ├── Docker Build & Push
│   └── Deploy to Kubernetes
└── post
    ├── always  (HTML reports)
    ├── success
    └── failure
```

### `environment`

```groovy
environment {
    IMAGE_NAME       = 'nelsonvillam/shop'
    IMAGE_TAG        = "${env.BUILD_NUMBER}"
    GRADLE_USER_HOME = "${env.WORKSPACE}/.gradle"
    SONAR_USER_HOME  = "${env.WORKSPACE}/.sonar"
    AWS_REGION       = 'sa-east-1'
}
```

| Variable | Purpose |
|---|---|
| `IMAGE_NAME` | Docker Hub repository for the shop image |
| `IMAGE_TAG` | Jenkins build number — used as the pinned image tag in K8s |
| `GRADLE_USER_HOME` | Redirects Gradle cache into the workspace to avoid permission errors inside Docker containers |
| `SONAR_USER_HOME` | Redirects SonarQube scanner cache for the same reason |
| `AWS_REGION` | AWS region ESO uses to reach Secrets Manager |

### Stage: `Checkout`

```groovy
stage('Checkout') {
    steps { checkout scm }
}
```

Clones `nelsonvillam/shop` at the commit that triggered the build.

### Stage: `Lint`

```groovy
stage('Lint') {
    agent {
        docker { image 'eclipse-temurin:21-jdk'; reuseNode true; args "-e HOME=${env.WORKSPACE}" }
    }
    steps {
        sh './gradlew checkstyleMain pmdMain spotbugsMain --no-daemon'
    }
}
```

Runs inside `eclipse-temurin:21-jdk`. Three static analysis tools run against the main source set:

| Task | Tool | What it checks |
|---|---|---|
| `checkstyleMain` | Checkstyle | Code style: naming, imports, formatting |
| `pmdMain` | PMD | Best practices and error-prone patterns |
| `spotbugsMain` | SpotBugs | Bug patterns in compiled bytecode |

**ErrorProne** also runs automatically during every compilation stage — it requires no separate task.

Fails immediately on any violation. HTML reports published in `post { always }`.

### Stage: `Tests` (parallel)

```groovy
stage('Tests') {
    failFast true
    parallel {
        stage('Unit Test') { ... }
        stage('Integration Test') { ... }
    }
}
```

`failFast true` — if either branch fails, the other is cancelled immediately.

#### Unit Test

```groovy
agent { docker { image 'eclipse-temurin:21-jdk'; reuseNode true; args "-e HOME=${env.WORKSPACE}" } }
sh './gradlew test --no-daemon'
```

- Runs inside `eclipse-temurin:21-jdk`
- Runs all classes **not** ending in `IT`
- `reuseNode true` mounts the same workspace so Gradle output is accessible
- JUnit XML results published to Jenkins always

#### Integration Test

```groovy
sh './gradlew integrationTest --no-daemon'
```

- Runs on the Jenkins **host** (no Docker container) so Testcontainers can reach the Docker daemon and spin up a real MongoDB instance
- Runs only classes ending in `IT`
- JUnit XML results published to Jenkins always

### Stage: `SonarQube Analysis`

```groovy
withSonarQubeEnv('sonarqube') {
    sh './gradlew jacocoTestReport sonar --no-daemon'
}
```

- `jacocoTestReport` merges `.exec` files from both `test` and `integrationTest` runs
- `sonar` sends the merged coverage report and source analysis to SonarCloud
- `withSonarQubeEnv('sonarqube')` injects the server URL and token — the name must match **Manage Jenkins → System → SonarQube servers**

### Stage: `Quality Gate`

```groovy
timeout(time: 5, unit: 'MINUTES') {
    waitForQualityGate abortPipeline: true
}
```

Polls SonarCloud for up to 5 minutes. If the gate fails, the pipeline is aborted — nothing is built or deployed.

### Stage: `Build`

```groovy
sh './gradlew bootJar --no-daemon'
```

Runs inside `eclipse-temurin:21-jdk`. Produces `build/libs/shop-0.0.1-SNAPSHOT.jar` — the fat JAR that is copied into the Docker image.

### Stage: `Docker Build & Push`

```groovy
sh "docker buildx create --use --name multibuilder 2>/dev/null || true"
sh """
    docker buildx build \
        --platform linux/amd64,linux/arm64 \
        -t ${IMAGE_NAME}:${IMAGE_TAG} \
        -t ${IMAGE_NAME}:latest \
        --push .
"""
```

Builds a multi-architecture image and pushes both the pinned tag and `latest` to Docker Hub. Authentication uses `~/.docker/config.json` on the Jenkins host — no `docker login` step is needed.

### Stage: `Deploy to Kubernetes`

```groovy
withCredentials([
    string(credentialsId: 'aws-access-key-id',     variable: 'CI_AWS_ACCESS_KEY_ID'),
    string(credentialsId: 'aws-secret-access-key', variable: 'CI_AWS_SECRET_ACCESS_KEY')
]) { ... }
```

Deploys **only the shop service**. Step by step:

1. Switch kubectl to `docker-desktop` context
2. Force-replace the `aws-credentials` k8s Secret with fresh values (`set +x` suppresses xtrace to avoid printing credentials)
3. Annotate the `aws-secretsmanager` SecretStore to force ESO to re-read credentials
4. Wait up to 60s for SecretStore to become `Ready`
5. Kick all three `ExternalSecret` resources to re-sync immediately
6. Wait up to 120s for each `ExternalSecret` to reach `Ready`
7. Apply `k8s/configmap.yaml` and `k8s/service.yaml`
8. Pin the image tag with `sed` and apply `k8s/deployment.yaml`
9. Wait for rollout: `kubectl rollout status deployment/shop --timeout=5m`

On failure: `kubectl rollout undo deployment/shop --namespace shop`

---

## gateway Jenkinsfile

### Structure at a glance

```
pipeline
├── agent
├── triggers
├── environment
├── stages
│   ├── Checkout
│   ├── Build
│   ├── Docker Build & Push
│   └── Deploy to Kubernetes
└── post
    ├── success
    └── failure
```

### `environment`

```groovy
environment {
    IMAGE_NAME       = 'nelsonvillam/gateway'
    IMAGE_TAG        = "${env.BUILD_NUMBER}"
    GRADLE_USER_HOME = "${env.WORKSPACE}/.gradle"
}
```

### Stage: `Build`

```groovy
agent { docker { image 'eclipse-temurin:21-jdk'; reuseNode true; args "-e HOME=${env.WORKSPACE}" } }
sh './gradlew bootJar --no-daemon'
```

Runs inside `eclipse-temurin:21-jdk`. Produces `build/libs/gateway-*.jar`.

### Stage: `Docker Build & Push`

Same `docker buildx` pattern as shop. Pushes `nelsonvillam/gateway:<BUILD_NUMBER>` and `nelsonvillam/gateway:latest`.

### Stage: `Deploy to Kubernetes`

Deploys **only the gateway service** — no ESO refresh needed (gateway reads `JWT_SECRET` from the `shop-secret` k8s Secret which is already managed by shop's pipeline).

```bash
sed 's|nelsonvillam/gateway:latest|nelsonvillam/gateway:<BUILD_NUMBER>|g' \
    k8s/deployment.yaml | kubectl apply -f -
kubectl apply -f k8s/service.yaml
kubectl apply -f k8s/ingress.yaml
kubectl rollout status deployment/gateway --namespace shop --timeout=5m
```

On failure: `kubectl rollout undo deployment/gateway --namespace shop`

---

## ping-service Jenkinsfile

### Structure at a glance

```
pipeline
├── agent
├── triggers
├── environment
├── stages
│   ├── Checkout
│   ├── Build
│   ├── Docker Build & Push
│   └── Deploy to Kubernetes
└── post
    ├── success
    └── failure
```

### `environment`

```groovy
environment {
    IMAGE_NAME       = 'nelsonvillam/ping-service'
    IMAGE_TAG        = "${env.BUILD_NUMBER}"
    GRADLE_USER_HOME = "${env.WORKSPACE}/.gradle"
}
```

### Stage: `Build`

```groovy
agent { docker { image 'eclipse-temurin:21-jdk'; reuseNode true; args "-e HOME=${env.WORKSPACE}" } }
sh './gradlew bootJar --no-daemon'
```

Produces `build/libs/ping-service-*.jar`.

### Stage: `Docker Build & Push`

Pushes `nelsonvillam/ping-service:<BUILD_NUMBER>` and `nelsonvillam/ping-service:latest`.

### Stage: `Deploy to Kubernetes`

Deploys **only the ping-service** — no secrets needed.

```bash
sed 's|nelsonvillam/ping-service:latest|nelsonvillam/ping-service:<BUILD_NUMBER>|g' \
    k8s/deployment.yaml | kubectl apply -f -
kubectl apply -f k8s/service.yaml
kubectl rollout status deployment/ping-service --namespace shop --timeout=5m
```

On failure: `kubectl rollout undo deployment/ping-service --namespace shop`

---

## `post` block

### `always` (shop only)

Publishes six HTML reports after every build regardless of result:

| Report | Source |
|---|---|
| Checkstyle | `build/reports/checkstyle/main.html` |
| PMD | `build/reports/pmd/main.html` |
| SpotBugs | `build/reports/spotbugs/main.html` |
| Unit Test | `build/reports/tests/test/index.html` |
| Integration Test | `build/reports/tests/integrationTest/index.html` |
| Coverage | `build/reports/jacoco/test/html/index.html` |

`allowMissing: true` prevents the post step failing if a report wasn't generated (e.g. build failed before tests ran).

### `success` / `failure`

All three pipelines print a status message. The `failure` block could be extended to send a Slack or email notification.
