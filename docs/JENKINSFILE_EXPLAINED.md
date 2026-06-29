# Jenkinsfile Explained

This document explains every block and line of the `Jenkinsfile` in detail.

---

## Full File at a Glance

```
pipeline
├── agent
├── triggers
├── environment
├── stages
│   ├── Checkout
│   ├── Lint
│   ├── Tests (parallel, failFast)
│   │   ├── Unit Test
│   │   └── Integration Test
│   ├── SonarQube Analysis
│   ├── Quality Gate
│   ├── Build
│   ├── Docker Build
│   ├── Docker Push
│   └── Deploy
└── post
    ├── always
    ├── success
    └── failure
```

---

## Top Level

```groovy
pipeline {
```

Declares a **declarative pipeline** — Jenkins' structured DSL for defining CI/CD workflows. Everything inside this block defines what the pipeline does, when it runs, and where.

---

## `agent`

```groovy
agent any
```

Tells Jenkins to run the pipeline on **any available agent** (executor). Since this is a local Jenkins instance with a single node, it always runs on that node. This is the default agent for all stages unless a stage overrides it with its own `agent` block.

---

## `triggers`

```groovy
triggers {
    githubPush()
}
```

Defines **when the pipeline is triggered automatically**. `githubPush()` tells Jenkins to start a new build whenever it receives a push event from GitHub via webhook. This requires:
- The GitHub plugin installed in Jenkins
- A webhook configured in the GitHub repository pointing to `https://<jenkins-url>/github-webhook/`
- ngrok running to expose the local Jenkins to GitHub (since Jenkins runs locally)

---

## `environment`

```groovy
environment {
    IMAGE_NAME         = 'nelsonvillam/shop'
    GATEWAY_IMAGE_NAME = 'nelsonvillam/gateway'
    PING_IMAGE_NAME    = 'nelsonvillam/ping-service'
    IMAGE_TAG          = "${env.BUILD_NUMBER}"
    GRADLE_USER_HOME   = "${env.WORKSPACE}/.gradle"
    SONAR_USER_HOME    = "${env.WORKSPACE}/.sonar"
    AWS_REGION         = 'sa-east-1'
}
```

Defines **environment variables available to all stages** in the pipeline.

| Variable | Value | Purpose |
|---|---|---|
| `IMAGE_NAME` | `nelsonvillam/shop` | Docker Hub repository name for the shop service |
| `GATEWAY_IMAGE_NAME` | `nelsonvillam/gateway` | Docker Hub repository name for the API gateway |
| `PING_IMAGE_NAME` | `nelsonvillam/ping-service` | Docker Hub repository name for the ping test microservice |
| `IMAGE_TAG` | `${env.BUILD_NUMBER}` | Jenkins auto-incremented build number — same tag applied to all three images (e.g. `42`) |
| `GRADLE_USER_HOME` | `${WORKSPACE}/.gradle` | Redirects Gradle's cache directory to the workspace, avoiding permission errors when running inside Docker containers where the default home (`/`) is read-only |
| `SONAR_USER_HOME` | `${WORKSPACE}/.sonar` | Redirects SonarQube scanner's cache to the workspace for the same reason |
| `AWS_REGION` | `sa-east-1` | AWS region used by ESO to reach Secrets Manager |

---

## `stages`

```groovy
stages {
```

Container block for all pipeline stages. Stages run **sequentially by default** — each one starts only after the previous one completes successfully (unless `parallel` is used).

---

### Stage: `Checkout`

```groovy
stage('Checkout') {
    steps {
        checkout scm
    }
}
```

**When it runs:** first, at the start of every build.

`checkout scm` clones or fetches the repository configured in the Jenkins job (GitHub in this case) and checks out the commit that triggered the build. `scm` refers to the Source Control Management configuration defined in the Jenkins job settings.

> Jenkins actually performs an implicit checkout before this stage too (the `Declarative: Checkout SCM` step visible in logs). This explicit `Checkout` stage makes it visible in the pipeline UI and allows it to be tracked separately.

---

### Stage: `Lint`

```groovy
stage('Lint') {
    agent {
        docker {
            image 'eclipse-temurin:21-jdk'
            reuseNode true
            args "-e HOME=${env.WORKSPACE}"
        }
    }
    steps {
        sh './gradlew checkstyleMain pmdMain spotbugsMain --no-daemon'
    }
}
```

**When it runs:** after Checkout, before Tests.

Runs three static analysis tools against the main source set inside an `eclipse-temurin:21-jdk` container:

| Task | Tool | What it checks |
|---|---|---|
| `checkstyleMain` | Checkstyle | Code style: naming conventions, no star imports, no unused imports |
| `pmdMain` | PMD | Best practices and error-prone patterns in source code |
| `spotbugsMain` | SpotBugs | Bug patterns in compiled bytecode |

A fourth tool, **ErrorProne**, runs automatically during compilation inside this stage (and every other stage that compiles Java) — it requires no separate task.

The build fails immediately if any violation is found. HTML reports for all three tools are published in `post { always { } }` so they appear even on failed builds.

---

### Stage: `Tests`

```groovy
stage('Tests') {
    failFast true
    parallel {
```

**When it runs:** after Lint completes.

- `failFast true` — if either parallel branch fails, the other is **cancelled immediately** rather than waiting for it to finish. This saves time on broken builds.
- `parallel` — runs Unit Test and Integration Test **simultaneously**. The pipeline waits for both to finish (or one to fail) before moving to SonarQube Analysis.

---

#### Parallel Branch: `Unit Test`

```groovy
stage('Unit Test') {
    agent {
        docker {
            image 'eclipse-temurin:21-jdk'
            reuseNode true
            args "-e HOME=${env.WORKSPACE}"
        }
    }
    steps {
        sh './gradlew test --no-daemon'
    }
    post {
        always {
            junit '**/build/test-results/test/**/*.xml'
        }
    }
}
```

**When it runs:** in parallel with Integration Test, after Checkout.

- `agent { docker { ... } }` — overrides the pipeline-level agent for this stage only. Jenkins spins up a temporary `eclipse-temurin:21-jdk` Docker container to run this stage, ensuring a clean and consistent Java 21 environment.
- `reuseNode true` — mounts the same workspace directory into the container so the Gradle build files and output are accessible without copying.
- `args "-e HOME=${env.WORKSPACE}"` — sets the `HOME` environment variable inside the container to the workspace path. This prevents `tree-sitter` and `jgit` from trying to write to `/` (the container's default home), which is read-only.
- `sh './gradlew test --no-daemon'` — runs all test classes **excluding** those ending in `IT` (integration tests). `--no-daemon` prevents Gradle from starting a background daemon inside the container, which would be wasted since the container is discarded after the stage.
- `junit '**/build/test-results/test/**/*.xml'` — runs **always** (even if tests fail) and publishes the JUnit XML results to Jenkins so test trends and failures are visible in the build UI.

---

#### Parallel Branch: `Integration Test`

```groovy
stage('Integration Test') {
    steps {
        sh './gradlew integrationTest --no-daemon'
    }
    post {
        always {
            junit '**/build/test-results/integrationTest/**/*.xml'
        }
    }
}
```

**When it runs:** in parallel with Unit Test, after Checkout.

- No `agent` block — runs directly on the Jenkins host (not inside a Docker container). This is intentional: integration tests use **Testcontainers**, which spins up a real MongoDB container. Testcontainers needs access to the Docker socket, which is simpler to access from the host than from inside a container.
- `sh './gradlew integrationTest --no-daemon'` — runs only test classes ending in `IT`.
- `junit` — same as unit tests, publishes results to Jenkins always.

---

### Stage: `SonarQube Analysis`

```groovy
stage('SonarQube Analysis') {
    agent {
        docker {
            image 'eclipse-temurin:21-jdk'
            reuseNode true
            args "-e HOME=${env.WORKSPACE}"
        }
    }
    steps {
        withSonarQubeEnv('sonarqube') {
            sh './gradlew jacocoTestReport sonar --no-daemon'
        }
    }
}
```

**When it runs:** after both Unit Test and Integration Test complete successfully.

- Runs inside an `eclipse-temurin:21-jdk` container (same reason as Unit Test — needs Java 21).
- `withSonarQubeEnv('sonarqube')` — injects the SonarCloud server URL and authentication token as environment variables. The name `'sonarqube'` must match the server name configured in **Manage Jenkins → Configure System → SonarQube servers**.
- `jacocoTestReport` — merges the `.exec` coverage data files produced by both `test` and `integrationTest` runs into a single XML + HTML report.
- `sonar` — sends the merged coverage report and source analysis to SonarCloud.

---

### Stage: `Quality Gate`

```groovy
stage('Quality Gate') {
    steps {
        timeout(time: 5, unit: 'MINUTES') {
            waitForQualityGate abortPipeline: true
        }
    }
}
```

**When it runs:** after SonarQube Analysis completes.

- `waitForQualityGate` — pauses the pipeline and polls SonarCloud until the analysis task finishes and a quality gate result is available.
- `abortPipeline: true` — if the quality gate status is `FAILED` (e.g. coverage dropped below the threshold, new bugs introduced), the pipeline is **aborted** and all subsequent stages are skipped.
- `timeout(time: 5, unit: 'MINUTES')` — if SonarCloud doesn't respond within 5 minutes, the stage fails rather than hanging indefinitely.

---

### Stage: `Build`

```groovy
stage('Build') {
    agent {
        docker {
            image 'eclipse-temurin:21-jdk'
            reuseNode true
            args "-e HOME=${env.WORKSPACE}"
        }
    }
    steps {
        sh './gradlew bootJar --no-daemon'
    }
}
```

**When it runs:** after Quality Gate passes.

- Runs inside `eclipse-temurin:21-jdk` to compile and package the application.
- `bootJar` — builds a Spring Boot **fat jar** containing the application and all its dependencies at `build/libs/shop-0.0.1-SNAPSHOT.jar`. This jar is what gets copied into the Docker image in the next stage.

---

### Stage: `Docker Build & Push` (parallel)

```groovy
stage('Docker Build & Push') {
    parallel {
        stage('Build shop image') { ... }
        stage('Build gateway image') { ... }
        stage('Build ping-service image') { ... }
    }
}
```

**When it runs:** after Build completes. All three branches run simultaneously.

Each branch uses `docker buildx` to build and push a multi-architecture image (`linux/amd64` + `linux/arm64`) tagged with both the build number and `latest`:

| Branch | Working directory | Steps |
|---|---|---|
| Build shop image | `.` (repo root) | `docker buildx build --push -t shop:42 -t shop:latest .` |
| Build gateway image | `gateway/` | `./gradlew bootJar` then `docker buildx build --push` |
| Build ping-service image | `ping-service/` | `./gradlew bootJar` then `docker buildx build --push` |

The gateway and ping-service each have their own `build.gradle` and `Dockerfile`, so they run `./gradlew bootJar` inside their subdirectory before the Docker build copies the JAR.

Authentication uses the credentials stored in `~/.docker/config.json` from a prior manual `docker login`. No `docker login` step is included in the pipeline because re-running it on every build would fail due to macOS Keychain restrictions in the Jenkins context.

---

### Stage: `Deploy to Kubernetes`

```groovy
stage('Deploy to Kubernetes') {
    steps {
        withCredentials([
            string(credentialsId: 'aws-access-key-id',     variable: 'CI_AWS_ACCESS_KEY_ID'),
            string(credentialsId: 'aws-secret-access-key', variable: 'CI_AWS_SECRET_ACCESS_KEY')
        ]) {
            sh """
                kubectl config use-context docker-desktop
                kubectl apply -k k8s/overlays/local/
                # refresh aws-credentials, annotate SecretStore, wait for ESO sync ...
                sed 's|...shop:latest|...shop:${IMAGE_TAG}|g' k8s/base/shop/deployment.yaml | kubectl apply -f -
                sed 's|...gateway:latest|...gateway:${IMAGE_TAG}|g' k8s/base/gateway/deployment.yaml | kubectl apply -f -
                sed 's|...ping-service:latest|...ping-service:${IMAGE_TAG}|g' k8s/base/ping-service/deployment.yaml | kubectl apply -f -
            """
        }
        sh """
            kubectl rollout status deployment/shop --namespace shop --timeout=5m
            kubectl rollout status deployment/gateway --namespace shop --timeout=5m
            kubectl rollout status deployment/ping-service --namespace shop --timeout=5m
        """
    }
    post {
        failure {
            sh """
                kubectl rollout undo deployment/shop --namespace shop || true
                kubectl rollout undo deployment/gateway --namespace shop || true
                kubectl rollout undo deployment/ping-service --namespace shop || true
            """
        }
    }
}
```

**When it runs:** after all three Docker images are built and pushed. Skipped if any earlier stage failed.

**What it does step by step:**

1. **Switch context** — `kubectl config use-context docker-desktop` targets the local cluster.
2. **Apply manifests** — `kubectl apply -k k8s/overlays/local/` creates or updates all Kubernetes resources (namespace, ExternalSecrets, SecretStore, deployments, services, ingress).
3. **Refresh AWS credentials** — force-replaces the `aws-credentials` k8s Secret with fresh values from Jenkins so ESO can authenticate to AWS Secrets Manager. `set +x` suppresses xtrace logging during this block to avoid printing credentials.
4. **Annotate SecretStore** — adds a `force-sync` annotation to trigger ESO to re-read the credentials immediately (ESO caches them and only re-reads on resource change).
5. **Wait for ESO** — waits up to 120s for all three `ExternalSecret` resources (`mongodb-credentials`, `mongodb-keyfile`, `shop-secret`) to reach `Ready` condition.
6. **Pin image tags** — `sed` rewrites each deployment's image tag from `:latest` to the exact build number, then `kubectl apply` triggers a rolling update.
7. **Wait for rollouts** — blocks the pipeline until all three deployments complete successfully.
8. **Auto-rollback on failure** — if any step fails, the `post { failure }` block runs `kubectl rollout undo` for all three deployments, restoring the previous ReplicaSet.

**Jenkins credentials required:**

| Credential ID | Type | Purpose |
|---|---|---|
| `aws-access-key-id` | Secret text | AWS access key for ESO → Secrets Manager auth |
| `aws-secret-access-key` | Secret text | AWS secret key for ESO → Secrets Manager auth |

---

## `post`

```groovy
post {
```

Defines actions that run **after all stages complete**, regardless of the pipeline result. There are three blocks used here.

---

### `always`

```groovy
always {
    publishHTML(target: [reportName: 'Checkstyle Report', reportDir: 'build/reports/checkstyle', reportFiles: 'main.html', ...])
    publishHTML(target: [reportName: 'PMD Report',        reportDir: 'build/reports/pmd',        reportFiles: 'main.html', ...])
    publishHTML(target: [reportName: 'SpotBugs Report',   reportDir: 'build/reports/spotbugs',   reportFiles: 'main.html', ...])
    publishHTML(target: [reportName: 'Unit Test Report',        reportDir: 'build/reports/tests/test',            reportFiles: 'index.html', ...])
    publishHTML(target: [reportName: 'Integration Test Report', reportDir: 'build/reports/tests/integrationTest', reportFiles: 'index.html', ...])
    publishHTML(target: [reportName: 'Coverage Report',         reportDir: 'build/reports/jacoco/test/html',      reportFiles: 'index.html', ...])
}
```

**When it runs:** after every build, whether it passed or failed.

Publishes six HTML reports to the Jenkins build page using the HTML Publisher plugin:

| Report | Source |
|---|---|
| Checkstyle Report | Checkstyle HTML report for style violations |
| PMD Report | PMD HTML report for best-practice violations |
| SpotBugs Report | SpotBugs HTML report for bug patterns |
| Unit Test Report | Gradle's HTML test report for the `test` task |
| Integration Test Report | Gradle's HTML test report for the `integrationTest` task |
| Coverage Report | JaCoCo HTML coverage report combining both test runs |

- `keepAll: true` — keeps reports from all previous builds, not just the latest.
- `allowMissing: true` — doesn't fail the post step if the report file wasn't generated (e.g. if the build failed before tests ran).
- `alwaysLinkToLastBuild: true` — the report link on the job page always points to the most recent build's report.

---

### `success`

```groovy
success {
    echo "Deployment of ${IMAGE_NAME}:${IMAGE_TAG} succeeded."
}
```

**When it runs:** only when all stages completed successfully.

Prints a confirmation message with the image name and build number to the build log.

---

### `failure`

```groovy
failure {
    echo "Pipeline failed. Check the logs above."
}
```

**When it runs:** when any stage failed and the pipeline was aborted.

Prints a failure message to the build log. This could be extended to send a Slack or email notification.
